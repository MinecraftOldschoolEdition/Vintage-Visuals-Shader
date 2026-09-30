package net.minecraft.src.graphics.backend.vulkan;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/** Headless hardware traversal of the production box/atlas/closest-hit shader against production BLAS/TLAS resources. */
public final class VulkanRayTracingGpuProbe {
    private final VulkanRuntime runtime = new VulkanRuntime();
    private VulkanRayTracingScene scene;
    private VulkanImmediateSubmitter submitter;
    private VulkanMappedBuffer output, rays, atlasRegions, staging;
    VulkanMappedBuffer transportSettings, materialData, entityData;
    VulkanMappedBuffer irradianceData, globalShadowData, coloredData;
    private ImageAllocation atlas;
    private long atlasView, atlasSampler, layout, pool, pipelineLayout, pipeline, shader;
    private long[] sets;
    private int assertions;
    private static final int RAY_COUNT = 8;
    static final int GLOBAL_SHADOW_BYTES = VulkanEmissiveLightUniforms.SHADOW_UNIFORM_BYTES;

    public static void main(String[] args) throws Exception {
        VulkanRayTracingGpuProbe probe = new VulkanRayTracingGpuProbe();
        try {
            probe.initialize();
            probe.verify();
            System.out.println("Hardware ray query GPU PASS: " + probe.assertions
                + " assertions; real BLAS/TLAS, production closest-hit/atlas alpha, frame slots, rebuild, growth and disable/reenable");
        } finally { probe.close(); }
    }

    void initialize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo application = VkApplicationInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("Ray query acceptance probe")).apiVersion(VK12.VK_API_VERSION_1_2);
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(application).ppEnabledExtensionNames(stack.pointers(stack.UTF8(KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME)));
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkCreateInstance(info, null, handle), "instance");
            this.runtime.device.instance = new VkInstance(handle.get(0), info);
            var count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(this.runtime.device.instance, count, null), "enumerate devices");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(this.runtime.device.instance, count, devices), "physical devices");
            for (int deviceIndex = 0; deviceIndex < devices.remaining() && this.runtime.device.physicalDevice == null; ++deviceIndex) {
                VkPhysicalDevice physical = new VkPhysicalDevice(devices.get(deviceIndex), this.runtime.device.instance);
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
                VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, queues);
                for (int queue = 0; queue < queues.remaining(); ++queue) {
                    if ((queues.get(queue).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
                    VulkanRayTracingCapabilities support = VulkanRayTracingCapabilities.query(physical, queue);
                    if (!support.available) continue;
                    this.runtime.device.physicalDevice = physical;
                    this.runtime.device.graphicsQueueFamily = queue;
                    this.runtime.device.presentQueueFamily = queue;
                    break;
                }
            }
            if (this.runtime.device.physicalDevice == null) throw new AssertionError("No hardware ray-query device available for native acceptance");
            VulkanLogicalDeviceFactory.Result created = VulkanLogicalDeviceFactory.create(this.runtime.device.instance,
                this.runtime.device.physicalDevice, this.runtime.device.graphicsQueueFamily, this.runtime.device.presentQueueFamily,
                false, false, false, false, false, "");
            this.runtime.device.device = created.device;
            this.runtime.device.graphicsQueue = created.graphicsQueue;
            this.runtime.device.presentQueue = created.presentQueue;
            this.runtime.device.rayTracing = created.rayTracing;
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(this.runtime.device.physicalDevice, properties);
            System.out.println("Hardware device: " + properties.deviceNameString() + "; " + created.rayTracing.reason);
            this.runtime.device.commandEncoders.createCommandPool(this.runtime.device.resourceOperations, this.runtime.device.graphicsQueueFamily);
            this.submitter = new VulkanImmediateSubmitter(created.device, this.runtime.device.commandEncoders.commandPool(),
                this.runtime.device.resourceOperations, null);
            this.scene = new VulkanRayTracingScene(this.runtime);
            this.scene.createResources(3);
            if (!this.scene.supported()) throw new AssertionError(this.scene.capabilityReason());

            this.output = mapped(RAY_COUNT * 48, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "ray result");
            this.rays = mapped(RAY_COUNT * 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "ray inputs");
            this.atlasRegions = mapped(256 * 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "atlas regions");
            this.transportSettings = mapped(80 + 64 * 64 + VulkanPointLightGrid.STORAGE_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "transport settings");
            this.materialData = mapped(VulkanPbrState.STORAGE_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "transport materials");
            this.entityData = mapped(4096, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "transport entity triangles");
            this.irradianceData = mapped(VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,"hardware irradiance probe results");
            this.globalShadowData = mapped(GLOBAL_SHADOW_BYTES,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,"full world-lighting uniforms");
            this.coloredData = mapped(64,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,"empty colored atlas");
            for(VulkanMappedBuffer empty : new VulkanMappedBuffer[]{this.irradianceData,this.globalShadowData,this.coloredData})
                empty.write(0,empty==this.irradianceData?VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES:empty==this.globalShadowData?GLOBAL_SHADOW_BYTES:64,
                    bytes->{while(bytes.hasRemaining())bytes.put((byte)0);});
            this.entityData.write(0, 4096, bytes -> { while (bytes.hasRemaining()) bytes.put((byte)0); });
            var profiles = net.minecraft.src.graphics.api.TerrainMaterialProfiles.defaults();
            this.materialData.write(0, VulkanPbrState.STORAGE_BYTES, bytes -> {
                while (bytes.hasRemaining()) bytes.put((byte)0);
                bytes.position(0);
                VulkanPbrState.writeHeader(bytes, -24, -24, -24, new float[8]);
                bytes.position(VulkanPbrState.PROFILE_OFFSET);
                VulkanPbrState.writeMaterialData(bytes.slice(), profiles, profiles.packedMapTexels());
            });
            this.atlasRegions.write(0, 256 * 16, bytes -> {
                for (int i = 0; i < 256; ++i) bytes.order(ByteOrder.nativeOrder())
                    .putFloat(i == 0 ? 0 : 0.5f).putFloat(0).putFloat(1).putFloat(1);
            });
            makeAtlas(stack);
            var descriptors = this.runtime.device.resourceManagers.descriptorSets();
            this.layout = descriptors.createLayout("ray probe",
                VulkanDescriptorSetManager.LayoutBinding.storageBuffer(0, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.storageBuffer(1, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.storageBuffer(2, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.storageBuffer(6, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.storageBuffer(7, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.accelerationStructure(8, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.storageBuffer(9, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.combinedImageSampler(10, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.storageBuffer(11, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.storageBuffer(12, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.storageBuffer(13, VK_SHADER_STAGE_COMPUTE_BIT),
                VulkanDescriptorSetManager.LayoutBinding.uniformBuffer(14, VK_SHADER_STAGE_COMPUTE_BIT));
            this.pool = descriptors.createPool("ray probe", 3, 0,
                VulkanDescriptorSetManager.PoolSize.storageBuffer(27),
                VulkanDescriptorSetManager.PoolSize.uniformBuffer(3),
                VulkanDescriptorSetManager.PoolSize.accelerationStructure(3),
                VulkanDescriptorSetManager.PoolSize.combinedImageSampler(3));
            this.sets = descriptors.allocateMany(this.pool, this.layout, 3, "ray probe");
            for (int frame = 0; frame < 3; ++frame) {
                this.scene.updateDescriptors(this.sets[frame], frame);
                descriptors.updateBufferDescriptors(this.sets[frame],
                    VulkanDescriptorSetManager.BufferDescriptorWrite.storageBuffer(0, this.output.buffer(), 0, RAY_COUNT * 48),
                    VulkanDescriptorSetManager.BufferDescriptorWrite.storageBuffer(1, this.rays.buffer(), 0, RAY_COUNT * 32),
                    VulkanDescriptorSetManager.BufferDescriptorWrite.storageBuffer(2, this.transportSettings.buffer(), 0, 80 + 64 * 64 + VulkanPointLightGrid.STORAGE_BYTES),
                    VulkanDescriptorSetManager.BufferDescriptorWrite.storageBuffer(6, this.coloredData.buffer(), 0, 64),
                    VulkanDescriptorSetManager.BufferDescriptorWrite.storageBuffer(7, this.materialData.buffer(), 0, VulkanPbrState.STORAGE_BYTES),
                    VulkanDescriptorSetManager.BufferDescriptorWrite.storageBuffer(11, this.atlasRegions.buffer(), 0, 256 * 16),
                    VulkanDescriptorSetManager.BufferDescriptorWrite.storageBuffer(12, this.entityData.buffer(), 0, 4096),
                    VulkanDescriptorSetManager.BufferDescriptorWrite.storageBuffer(13, this.irradianceData.buffer(),0,VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES),
                    VulkanDescriptorSetManager.BufferDescriptorWrite.uniformBuffer(14, this.globalShadowData.buffer(),0,GLOBAL_SHADOW_BYTES));
                this.runtime.device.resourceManagers.imageResources().updateImageDescriptors(this.sets[frame],
                    VulkanImageResourceManager.ImageDescriptorWrite.combinedImageSampler(10, this.atlasSampler, this.atlasView,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            }
            makePipeline();
        }
    }

    private void verify() {
        float[] boxes = {0, 0, 2, 1, 1, 3, 0, 0, 5, 1, 1, 6, 2, 0, 2, 3, 1, 3};
        int[] data = {0, 0, 0, 0, 0, 0, 0xff8040, 196 | 0x2000,
            1, 1, 1, 1, 1, 1, 0xffffff, 192 | 0x1000,
            1, 1, 1, 1, 1, 1, 0xffffff, 0x8000};
        setRays();
        dispatch(0, null, null, 0, 0, false, false);
        for (int ray = 0; ray < RAY_COUNT; ++ray) expect(ray, -1, -1);
        for (int frame = 0; frame < 3; ++frame) {
            dispatch(frame, boxes, data, 3, 1, true, false);
            expectStandard(2);
        }
        float[] moved = boxes.clone(); moved[2] = 3; moved[5] = 4;
        dispatch(0, moved, data, 3, 2, true, false);
        expect(0, 0, 3); expect(1, 1, 5);
        dispatch(1, null, null, 0, 0, false, false);
        expectStandard(2);
        dispatch(0, null, null, 0, 0, false, true);
        for (int ray = 0; ray < RAY_COUNT; ++ray) expect(ray, -1, -1);
        dispatch(1, null, null, 0, 0, false, false);
        expectStandard(2);
        dispatch(0, moved, data, 3, 2, true, false);
        expect(0, 0, 3);
        float[] grown = new float[64 * 6]; int[] grownData = new int[64 * 8];
        System.arraycopy(boxes, 0, grown, 0, boxes.length); System.arraycopy(data, 0, grownData, 0, data.length);
        for (int p = 3; p < 64; ++p) {
            int i = p * 6; grown[i] = 100 + p; grown[i + 1] = 100; grown[i + 2] = 100;
            grown[i + 3] = 101 + p; grown[i + 4] = 101; grown[i + 5] = 101;
            grownData[p * 8 + 6] = 0xffffff; grownData[p * 8 + 7] = 192 | 0x1000;
        }
        dispatch(2, grown, grownData, 64, 3, true, false);
        expectStandard(2);
        dispatch(2, null, null, 0, 4, true, false);
        for (int ray = 0; ray < RAY_COUNT; ++ray) expect(ray, -1, -1);
        verifyInvalidRays();
        verifyInstanceOffsets();
    }

    private void verifyInstanceOffsets() {
        float[] inputs=new float[RAY_COUNT*8];
        for(int ray=0;ray<RAY_COUNT;++ray) {
            inputs[ray*8]=.5f;inputs[ray*8+1]=.5f;inputs[ray*8+3]=100;inputs[ray*8+6]=1;inputs[ray*8+7]=1;
        }
        // The fluid comes first in canonical storage but last in BLAS order.
        // Interior rays must still see an entity embedded in that fluid.
        float[] terrain={0,0,1,1,1,4, 0,0,5,1,1,6},moving={0,0,2,1,1,3};
        int[] material={1,1,1,1,1,1,0xffffff,192|4096};
        int top=1|(65535<<8);
        int[] terrainMaterial={top,top,top,top,1,1,0xffffff,202|16384|131072,
            1,1,1,1,1,1,0xffffff,192|4096};
        this.runtime.frameState.currentFrame=0;
        for(boolean dynamic:new boolean[]{true,false}) for(int category:new int[]{1,21}) {
            for(int ray=0;ray<RAY_COUNT;++ray)inputs[ray*8+7]=category;
            writeRayInputs(inputs);
            if(!this.submitter.submitAndWait("solid/fluid/dynamic instance indexing",Long.MAX_VALUE,(encoder,stack)->{
                VkCommandBuffer command=new VkCommandBuffer(encoder.commandBufferAddress(),this.runtime.device.device);
                try {
                    var prepare=VulkanRayTracingScene.class.getDeclaredMethod("preparePartitions",VkCommandBuffer.class,
                        float[].class,int[].class,int.class,long.class,float[].class,int[].class,int.class,long.class,
                        int.class,int.class,int.class);
                    prepare.setAccessible(true);
                    prepare.invoke(this.scene,command,terrain,terrainMaterial,2,71L,dynamic?moving:null,dynamic?material:null,dynamic?1:0,
                        dynamic?72L:73L,30_000_000,128,-30_000_000);
                } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
                this.scene.updateDescriptors(this.sets[0],0);
                VkMemoryBarrier.Buffer before=VkMemoryBarrier.calloc(1,stack).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,before,null,null);
                vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,this.pipeline);
                vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,this.pipelineLayout,0,stack.longs(this.sets[0]),null);
                vkCmdDispatch(command,1,1,1);
                VkMemoryBarrier.Buffer after=VkMemoryBarrier.calloc(1,stack).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,after,null,null);
            }))throw new AssertionError("Instance indexing dispatch incomplete");
            for(int ray=0;ray<RAY_COUNT;++ray)expect(ray,category==1?0:dynamic?2:1,category==1?1:dynamic?2:5);
        }
    }

    private void verifyInvalidRays() {
        float[] inputs = new float[RAY_COUNT * 8];
        for (int ray = 0; ray < RAY_COUNT; ++ray) {
            int offset = ray * 8;
            inputs[offset] = .75f;
            inputs[offset + 1] = .5f;
            inputs[offset + 3] = 100.0f;
            inputs[offset + 6] = 1.0f;
            inputs[offset + 7] = 1.0f;
        }
        inputs[0] = Float.NaN;
        inputs[8] = Float.POSITIVE_INFINITY;
        inputs[2 * 8 + 4] = Float.NaN;
        inputs[3 * 8 + 4] = Float.NEGATIVE_INFINITY;
        inputs[4 * 8 + 6] = 0.0f;
        inputs[5 * 8 + 3] = Float.NaN;
        inputs[6 * 8 + 3] = Float.POSITIVE_INFINITY;
        inputs[7 * 8 + 3] = -1.0f;
        writeRayInputs(inputs);
        dispatch(0, new float[]{0,0,2,1,1,3}, new int[]{1,1,1,1,1,1,0xffffff,192|4096},
            1, 99, true, false);
        for (int ray = 0; ray < RAY_COUNT; ++ray) expect(ray, -1, -1);
    }

    private void expectStandard(float firstDistance) {
        expect(0, 0, firstDistance); expect(1, 1, 5); expect(2, -1, -1); expect(3, 0, 0.5f);
        expect(4, -1, -1); expect(5, 1, 2); expect(6, -1, -1); expect(7, 2, 2);
        ByteBuffer result = results();
        near(-1, result.getFloat(16 + 8), "front face normal");
        near(1, result.getFloat(5 * 48 + 16 + 8), "reverse face normal");
        near(1, result.getFloat(3 * 48 + 16 + 8), "inside exit normal");
        near(128f / 255, result.getFloat(32 + 4), "surface tint green");
        near(64f / 255, result.getFloat(32 + 8), "surface tint blue");
    }

    private void setRays() {
        float[][] origins = {{.75f,.5f,0},{.25f,.5f,0},{1.5f,.5f,0},{.25f,.5f,2.5f},
            {.75f,.5f,0},{.75f,.5f,8},{2.5f,.5f,0},{2.5f,.5f,0}};
        this.rays.write(0, RAY_COUNT * 32, bytes -> {
            bytes.order(ByteOrder.nativeOrder());
            for (int i = 0; i < RAY_COUNT; ++i) {
                for (float value : origins[i]) bytes.putFloat(value);
                bytes.putFloat(i == 4 ? 1.9f : 100);
                bytes.putFloat(0).putFloat(0).putFloat(i == 5 ? -1 : 1).putFloat(i == 7 ? 2 : 1);
            }
        });
    }

    void writeRayInputs(float[] values) {
        if (values.length != RAY_COUNT * 8) throw new IllegalArgumentException("The native fixture requires eight packed rays");
        this.rays.write(0, RAY_COUNT * 32, bytes -> bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().put(values));
    }

    void dispatch(int frame, float[] boxes, int[] data, int count, long revision, boolean prepare, boolean disable) {
        dispatch(frame, boxes, data, count, revision, prepare, disable, 1);
    }

    void dispatch(int frame, float[] boxes, int[] data, int count, long revision, boolean prepare, boolean disable, int groups) {
        this.runtime.frameState.currentFrame = frame;
        if (disable) this.scene.disableCurrentFrame();
        boolean done = this.submitter.submitAndWait("ray query fixture", Long.MAX_VALUE, (encoder, stack) -> {
            VkCommandBuffer command = new VkCommandBuffer(encoder.commandBufferAddress(), this.runtime.device.device);
            if (prepare) this.scene.prepareFrame(command, boxes, data, count, 30_000_000, 128, -30_000_000, revision);
            this.scene.updateDescriptors(this.sets[frame], frame);
            VkMemoryBarrier.Buffer before = VkMemoryBarrier.calloc(1, stack).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, before, null, null);
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0,
                stack.longs(this.sets[frame]), null);
            vkCmdDispatch(command, groups, 1, 1);
            VkMemoryBarrier.Buffer after = VkMemoryBarrier.calloc(1, stack).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, after, null, null);
        });
        if (!done) throw new AssertionError("Ray query dispatch did not finish");
    }

    private void makePipeline() {
        String production = VulkanRayTracingShader.SOURCE;
        production = production.substring(production.indexOf("#ifdef HARDWARE_RAY_TRACING"),
            production.indexOf("vec3 rtDirection(")) + "\n#endif\n";
        production = production.replace("set=3", "set=0");
        String world = VulkanPbrWorldShader.SOURCE;
        String materialHeader = world.substring(world.indexOf("layout(std430, set = 3, binding = 7)"),
            world.indexOf("vec4 pbrMapTexel("));
        String code = """
            #version 460
            #extension GL_EXT_ray_query : require
            #define HARDWARE_RAY_TRACING 1
            struct ShadowProbe { vec4 materialParams; };
            const ShadowProbe shadowData=ShadowProbe(vec4(0,0,0,1));
            layout(local_size_x=8) in;
            struct Ray { vec4 origin; vec4 direction; };
            layout(std430,set=0,binding=1) readonly buffer Inputs { Ray rays[]; } inputData;
            layout(std430,set=0,binding=0) buffer Outputs { vec4 results[]; } outputData;
            """ + VulkanPbrShader.SOURCE + materialHeader.replace("set = 3", "set = 0") + production + """
            void main() {
                uint lane=gl_GlobalInvocationID.x;
                Ray r=inputData.rays[lane];
                RtHit hit;
                bool found=rtClosest(r.origin.xyz,r.direction.xyz,r.origin.w,int(r.direction.w),hit);
                outputData.results[lane*3u]=found ? vec4(float(hit.primitive),hit.distance,hit.exitDistance,float(hit.optics)) : vec4(-1);
                outputData.results[lane*3u+1u]=found ? vec4(hit.normal,0) : vec4(0);
                outputData.results[lane*3u+2u]=found ? hit.surface : vec4(0);
            }
            """;
        replaceProgram(code);
    }

    void replaceProgram(String code) {
        var old = this.runtime.device.resourceManagers.pipelines();
        old.destroyPipeline(this.pipeline); old.destroyPipelineLayout(this.pipelineLayout); old.destroyShaderModule(this.shader);
        this.pipeline = this.pipelineLayout = this.shader = 0;
        ByteBuffer compiled = VulkanShaderCompiler.compileCompute(code, "production-ray-query.comp");
        try {
            var manager = this.runtime.device.resourceManagers.pipelines();
            this.shader = manager.createShaderModule(compiled, "production ray query");
            this.pipelineLayout = manager.createPipelineLayout("ray query", new long[]{this.layout});
            this.pipeline = manager.createComputePipeline("ray query", this.pipelineLayout, this.shader);
        } finally {
            MemoryUtil.memFree(compiled);
        }
    }

    private void makeAtlas(MemoryStack stack) {
        VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM).mipLevels(1).arrayLayers(1)
            .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        imageInfo.extent().set(2, 1, 1);
        this.atlas = this.runtime.device.resourceManagers.imageAllocator().createDeviceLocalImage(imageInfo);
        this.atlasView = this.runtime.device.resourceManagers.imageResources().create2DImageView(this.atlas.image,
            VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, "ray alpha atlas");
        this.atlasSampler = this.runtime.device.resourceManagers.imageResources().createNearestClampSampler(0, "ray atlas");
        this.staging = mapped(8, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, "atlas pixels");
        this.staging.write(0, 8, bytes -> bytes.put(new byte[]{-1,-1,-1,0,-1,-1,-1,-1}));
        if (!this.submitter.submitAndWait("ray atlas upload", Long.MAX_VALUE, (encoder, memory) -> {
            VkCommandBuffer command = new VkCommandBuffer(encoder.commandBufferAddress(), this.runtime.device.device);
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, memory).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .image(this.atlas.image).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, memory);
            copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.imageExtent().set(2, 1, 1);
            vkCmdCopyBufferToImage(command, this.staging.buffer(), this.atlas.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);
        })) throw new AssertionError("Atlas upload failed");
    }

    private VulkanMappedBuffer mapped(long bytes, int usage, String label) {
        VulkanMappedBuffer buffer = this.runtime.device.resourceOperations.createMappedBuffer(usage, label);
        buffer.ensureCapacity(bytes); return buffer;
    }
    ByteBuffer results() { return MemoryUtil.memByteBuffer(this.output.mappedAddress(), RAY_COUNT * 48).order(ByteOrder.nativeOrder()); }
    private void expect(int ray, int primitive, float distance) {
        ByteBuffer result = results();
        near(primitive, result.getFloat(ray * 48), "ray " + ray + " primitive");
        near(distance, result.getFloat(ray * 48 + 4), "ray " + ray + " distance");
    }
    private void near(float expected, float actual, String label) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > 0.0001f)
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        ++this.assertions;
    }
    private static void check(int code, String operation) { if (code != VK_SUCCESS) throw new IllegalStateException(operation + ": " + code); }
    void close() {
        if (this.runtime.device.device != null) {
            vkDeviceWaitIdle(this.runtime.device.device);
            var pipelines = this.runtime.device.resourceManagers.pipelines();
            pipelines.destroyPipeline(this.pipeline); pipelines.destroyPipelineLayout(this.pipelineLayout); pipelines.destroyShaderModule(this.shader);
            var descriptors = this.runtime.device.resourceManagers.descriptorSets();
            descriptors.destroyPool(this.pool); descriptors.destroyLayout(this.layout);
            var images = this.runtime.device.resourceManagers.imageResources();
            images.destroySampler(this.atlasSampler); images.destroyImageView(this.atlasView);
            this.runtime.device.resourceManagers.imageAllocator().destroy(this.atlas);
            for (VulkanMappedBuffer buffer : new VulkanMappedBuffer[]{this.output,this.rays,this.atlasRegions,this.staging,
                this.transportSettings,this.materialData,this.entityData,this.irradianceData,this.globalShadowData,this.coloredData}) if (buffer != null) buffer.destroy();
            if (this.scene != null) this.scene.destroyResources();
            this.runtime.device.commandEncoders.destroyCommandPool(this.runtime.device.resourceOperations);
            vkDestroyDevice(this.runtime.device.device, null);
        }
        if (this.runtime.device.instance != null) vkDestroyInstance(this.runtime.device.instance, null);
    }
}
