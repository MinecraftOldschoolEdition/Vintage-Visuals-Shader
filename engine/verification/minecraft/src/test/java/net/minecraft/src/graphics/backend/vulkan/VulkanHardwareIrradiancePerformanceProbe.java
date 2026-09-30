package net.minecraft.src.graphics.backend.vulkan;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/** Explicit native compute benchmark. AS building and host writes are outside GPU timestamps. */
public final class VulkanHardwareIrradiancePerformanceProbe {
    public static void main(String[] args) throws Exception {
        VulkanRayTracingGpuProbe gpu=new VulkanRayTracingGpuProbe();
        long queries=0;
        try {
            gpu.initialize();
            String sourcePath = System.getProperty("gi.probe.computeSource");
            gpu.replaceProgram(sourcePath == null ? VulkanHardwareIrradianceGpuProbe.computeSource() : Files.readString(Path.of(sourcePath)));
            int probesPerGroup = Integer.getInteger("gi.probe.probesPerGroup", VulkanHardwareIrradianceShader.PROBES_PER_WORKGROUP);
            VulkanRuntime runtime=field(gpu,"runtime",VulkanRuntime.class);
            VulkanImmediateSubmitter submitter=field(gpu,"submitter",VulkanImmediateSubmitter.class);
            long pipeline=field(gpu,"pipeline",Long.class),layout=field(gpu,"pipelineLayout",Long.class);
            long set=field(gpu,"sets",long[].class)[0];
            float[] bounds=new float[50_000*6];int[] materials=new int[50_000*8];
            int count=0;
            for(int cell=0;count<50_000;++cell) {
                int x=cell%100-50,z=cell/100%100-50,y=cell/10_000-1;
                if(Math.abs(x)<=3 && y>=0 && y<4)continue;
                int at=count*6;bounds[at]=x;bounds[at+1]=y;bounds[at+2]=z;
                bounds[at+3]=x+1;bounds[at+4]=y+1;bounds[at+5]=z+1;
                Arrays.fill(materials,count*8,count*8+6,1);
                materials[count*8+6]=0xffeeb4;
                materials[count*8+7]=count%4000==0 ? 203|15<<8|4096 : 192|4096;
                ++count;
            }
            gpu.globalShadowData.write(0,VulkanRayTracingGpuProbe.GLOBAL_SHADOW_BYTES,bytes->{
                bytes.order(ByteOrder.nativeOrder());while(bytes.hasRemaining())bytes.put((byte)0);
                bytes.putFloat(348,1);bytes.putFloat(372,1);bytes.putFloat(3644,1);
            });
            // A zero update count constructs the real scene without tracing probes.
            gpu.dispatch(0,bounds,materials,count,1,true,false);
            float period;
            try(MemoryStack stack=MemoryStack.stackPush()) {
                var properties=VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(runtime.device.physicalDevice,properties);
                period=properties.limits().timestampPeriod();
                var info=VkQueryPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2);
                var handle=stack.mallocLong(1);
                check(vkCreateQueryPool(runtime.device.device,info,null,handle),"query pool");queries=handle.get(0);
            }
            for(int quality=0;quality<4;++quality) {
                final int tier=quality,budget=VulkanHardwareIrradianceVolume.budgetFor(quality);
                gpu.irradianceData.write(0,VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES,bytes->{
                    bytes.order(ByteOrder.nativeOrder());while(bytes.hasRemaining())bytes.put((byte)0);
                    bytes.putInt(0,2048).putInt(4,1).putInt(12,budget);
                    bytes.putInt(44,VulkanHardwareIrradianceVolume.rayCountFor(tier));
                    for(int i=0;i<2048;++i) {
                        int at=(VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS+i*28)*4;
                        bytes.putFloat(at,(i%5)-2+.5f).putFloat(at+4,(i/5%4)+.5f)
                            .putFloat(at+8,(i/20%90)-45+.5f);
                    }
                });
                double[] milliseconds=new double[25];
                final long queryPool=queries;
                for(int sample=0;sample<milliseconds.length;++sample) {
                    if(!submitter.submitAndWait("hardware irradiance GPU timestamp",Long.MAX_VALUE,(encoder,stack)->{
                        var command=new VkCommandBuffer(encoder.commandBufferAddress(),runtime.device.device);
                        var before=VkMemoryBarrier.calloc(1,stack).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                            .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
                        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,before,null,null);
                        vkCmdResetQueryPool(command,queryPool,0,2);
                        vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
                        vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,stack.longs(set),null);
                        vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queryPool,0);
                        vkCmdDispatch(command,(budget+probesPerGroup-1)/probesPerGroup,1,1);
                        vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queryPool,1);
                    }))throw new AssertionError("Compute timestamp submission failed");
                    try(MemoryStack stack=MemoryStack.stackPush()) {
                        var values=stack.mallocLong(2);
                        check(vkGetQueryPoolResults(runtime.device.device,queries,0,2,values,8,
                            VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT),"timestamp results");
                        milliseconds[sample]=(values.get(1)-values.get(0))*period/1_000_000.0;
                    }
                }
                double cold=milliseconds[0];double[] warmed=Arrays.copyOfRange(milliseconds,6,milliseconds.length);Arrays.sort(warmed);
                System.out.printf("Hardware irradiance GPU quality=%d staticAabbs=%d probes=2048 updates=%d raysPerProbe=%d coldMs=%.4f warmedMedianMs=%.4f warmedMaxMs=%.4f%n",
                    quality,count,budget,VulkanHardwareIrradianceVolume.rayCountFor(quality),cold,warmed[9],warmed[18]);
                String output = System.getProperty("gi.probe.results");
                if (output != null) {
                    Path directory = Path.of(output); Files.createDirectories(directory);
                    var data = org.lwjgl.system.MemoryUtil.memByteBuffer(gpu.irradianceData.mappedAddress(), VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES);
                    data.position(VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS * 4);
                    byte[] records = new byte[budget * VulkanHardwareIrradianceVolume.RECORD_WORDS * 4];
                    data.get(records); Files.write(directory.resolve("quality-" + quality + ".bin"), records);
                }
            }
        } finally {
            if(queries!=0)vkDestroyQueryPool(field(gpu,"runtime",VulkanRuntime.class).device.device,queries,null);
            gpu.close();
        }
    }
    private static <T>T field(Object value,String name,Class<T> type) throws ReflectiveOperationException {
        var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(value));
    }
    private static void check(int result,String operation) {if(result!=VK_SUCCESS)throw new IllegalStateException(operation+": "+result);}
}
