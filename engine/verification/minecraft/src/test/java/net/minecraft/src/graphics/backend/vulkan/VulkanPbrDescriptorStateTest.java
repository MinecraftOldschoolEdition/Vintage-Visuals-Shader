package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.src.graphics.api.ShaderLightingMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.system.MemoryUtil;
import sun.misc.Unsafe;

/** Exercises descriptor-time option handling and real host writes without creating a Vulkan device. */
public class VulkanPbrDescriptorStateTest {
    private static final String PBR = "minecraft.vulkan.pbrMaterials.enabled";
    private static final String GI = "minecraft.vulkan.coloredGlobalIllumination.enabled";
    private static final String SUN = "minecraft.vulkan.dynamicShadows.enabled";
    private String previousLightingMode;

    @Test
    public void waterAnimationUpdatesOnlyFrameHeaderWithoutRebuildingOrUploadingMaterialVolume() throws Exception {
        VulkanRuntime runtime = new VulkanRuntime();
        VulkanPbrState state = new VulkanPbrState(runtime);
        var world = new net.minecraft.src.world.core.LightweightTestWorld("caustic-animation-header");
        ByteBuffer memory = MemoryUtil.memCalloc(VulkanPbrState.STORAGE_BYTES).order(ByteOrder.nativeOrder());
        try {
            set(state, "buffers", new VulkanMappedBuffer[]{mappedBuffer(memory)});
            set(state, "uploadedProfileRevisions", new long[]{Long.MIN_VALUE});
            set(state, "uploadedVolumeRevisions", new long[]{Long.MIN_VALUE});
            set(state, "uploadedWaterRevisions", new long[]{Long.MIN_VALUE});
            set(state, "enabledFrames", new boolean[1]);
            world.setWorldTime(6000);
            world.getWorldInfo().setTotalTime(6000);
            assertTrue(state.prepareFrame(true, world, 8, 64, 8, 0, 64, 0));
            VulkanPbrMaterialVolume volume = (VulkanPbrMaterialVolume)get(state, "volume");
            long revision = volume.revision();
            long uploaded = ((long[])get(state, "uploadedVolumeRevisions"))[0];
            byte[] before = new byte[VulkanPbrState.STORAGE_BYTES - VulkanPbrState.HEADER_BYTES];
            memory.duplicate().position(VulkanPbrState.HEADER_BYTES).get(before);
            assertEquals(44.0f, memory.getFloat(44), 0.00001f);
            // Freeze the solar clock while ordinary texture/game ticks continue.
            world.getWorldInfo().setTotalTime(6020);
            assertTrue(state.prepareFrame(true, world, 8, 64, 8, 0, 64, 0));
            assertEquals(45.0f, memory.getFloat(44), 0.00001f);
            assertEquals(6000, world.getWorldTime());
            assertSame(volume, get(state, "volume"));
            assertEquals(revision, volume.revision());
            assertEquals(uploaded, ((long[])get(state, "uploadedVolumeRevisions"))[0]);
            byte[] after = new byte[before.length];
            memory.duplicate().position(VulkanPbrState.HEADER_BYTES).get(after);
            org.junit.Assert.assertArrayEquals("animation cannot rewrite profiles, maps or voxel payload", before, after);
        } finally { MemoryUtil.memFree(memory); }
    }

    @Test
    public void waterColumnsUpdateOnlyTheFencedSlotAndTrackReferenceWithoutRebuilding() throws Exception {
        VulkanRuntime runtime=new VulkanRuntime();
        VulkanPbrState state=new VulkanPbrState(runtime);
        var world=new net.minecraft.src.world.core.LightweightTestWorld("caustic-columns-fenced");
        var chunk=world.prepareTestChunk(2,0);chunk.hasChunkData=true;
        for(int y=58;y<64;++y)chunk.blocks[4<<11|4<<7|y]=(byte)net.minecraft.src.block.Block.waterStill.blockID;
        chunk.heightMap[4<<4|4]=64;chunk.rebuildNonEmptySectionSummary();
        ByteBuffer[] memory=new ByteBuffer[2];
        try {
            VulkanMappedBuffer[] buffers=new VulkanMappedBuffer[2];
            for(int i=0;i<2;++i) {memory[i]=MemoryUtil.memCalloc(VulkanPbrState.STORAGE_BYTES).order(ByteOrder.nativeOrder());buffers[i]=mappedBuffer(memory[i]);}
            set(state,"buffers",buffers);set(state,"enabledFrames",new boolean[2]);
            for(String field:new String[]{"uploadedProfileRevisions","uploadedVolumeRevisions","uploadedWaterRevisions"})
                set(state,field,new long[]{Long.MIN_VALUE,Long.MIN_VALUE});
            int offset=VulkanPbrState.WATER_COLUMN_OFFSET;
            assertTrue(state.prepareFrame(true,world,8,64,0,32,64,0));
            VulkanPbrWaterColumns columns=(VulkanPbrWaterColumns)get(state,"waterColumns");
            long revision=columns.revision();
            int cell=36-columns.originX()+128*(4-columns.originZ());
            assertEquals((64<<16)|58,memory[0].getInt(offset+16+cell*4));
            assertEquals(0,memory[1].getInt(offset+12));
            runtime.frameState.currentFrame=1;
            assertTrue(state.prepareFrame(true,world,8,96,0,64,96,32));
            assertSame(columns,get(state,"waterColumns"));assertEquals(revision,columns.revision());
            assertEquals(columns.originX()-64,memory[1].getInt(offset));
            assertEquals(96,memory[1].getInt(offset+4));
            assertEquals(columns.originZ()-32,memory[1].getInt(offset+8));
            assertEquals(64,memory[0].getInt(offset+4));
            assertEquals((64<<16)|58,memory[1].getInt(offset+16+cell*4));
            chunk.blocks[4<<11|4<<7|63]=(byte)net.minecraft.src.block.Block.stone.blockID;
            chunk.setBlockMetadata(4,63,4,1);
            assertTrue(state.prepareFrame(true,world,8,96,0,64,96,32));
            assertEquals(0,memory[1].getInt(offset+16+cell*4));
            assertEquals((64<<16)|58,memory[0].getInt(offset+16+cell*4));
            runtime.frameState.currentFrame=0;
            assertTrue(state.prepareFrame(true,world,8,96,0,64,96,32));
            assertEquals(0,memory[0].getInt(offset+16+cell*4));
        } finally {for(ByteBuffer bytes:memory)if(bytes!=null)MemoryUtil.memFree(bytes);}
    }

    @Test
    public void causticClockWrapsAtAnExactWavePeriodAndRejectsInvalidPartialTicks() {
        assertEquals(0.0f, VulkanPbrState.waterAnimationSeconds(0, 0), 0);
        assertEquals(256.0f, VulkanPbrState.waterAnimationSeconds(5119, 1), 0);
        assertEquals(0.0f, VulkanPbrState.waterAnimationSeconds(5120, 0), 0);
        assertEquals(VulkanPbrState.waterAnimationSeconds(-1, .5f),
            VulkanPbrState.waterAnimationSeconds(5119, .5f), 0);
        assertEquals(1.0f, VulkanPbrState.waterAnimationSeconds(20, Float.NaN), 0);
        assertEquals(1.0f, VulkanPbrState.waterAnimationSeconds(20, Float.POSITIVE_INFINITY), 0);
    }

    @Before
    public void enableVintageVisualsForEffectStateFixtures() {
        previousLightingMode = System.getProperty(ShaderLightingMode.REQUESTED_MODE_PROPERTY);
        System.setProperty(ShaderLightingMode.REQUESTED_MODE_PROPERTY, "rasterized");
    }

    @After
    public void restoreLightingMode() {
        if (previousLightingMode == null) System.clearProperty(ShaderLightingMode.REQUESTED_MODE_PROPERTY);
        else System.setProperty(ShaderLightingMode.REQUESTED_MODE_PROPERTY, previousLightingMode);
    }

    @Test
    public void frameCaptureDropsStaleHardwareModeBeforeAnyReceiverAndDisablesOnlyTheFencedFrame() throws Exception {
        Map<String, String> previous = new HashMap<>();
        for (String property : new String[]{PBR, GI, SUN, "minecraft.vulkan.emissiveShadowCasting.enabled"}) {
            previous.put(property, System.getProperty(property));
            System.setProperty(property, "false");
        }
        String activeProperty = "minecraft.vulkan.hardwareRayTracing.active";
        previous.put(activeProperty, System.getProperty(activeProperty));
        int stride = 16384;
        int frameCount = VulkanRuntimeConfig.MAX_FRAMES_IN_FLIGHT;
        try {
            for (boolean enabled : new boolean[]{false, true}) for (int currentFrame = 0; currentFrame < frameCount; ++currentFrame) {
                System.setProperty(PBR, Boolean.toString(enabled));
                VulkanRuntime runtime = new VulkanRuntime();
                runtime.frameState.currentFrame = currentFrame;
                VulkanDynamicShadowState state = runtime.dynamicShadowState;
                ByteBuffer uniforms = MemoryUtil.memAlloc(stride * frameCount).order(ByteOrder.nativeOrder());
                ByteBuffer[] primitives = new ByteBuffer[frameCount];
                try {
                    for (int offset = 0; offset < uniforms.capacity(); offset += 4)
                        uniforms.putInt(offset, 0x5a5a5a5a);
                    set(state, "uniformBuffer", mappedBuffer(uniforms));
                    set(state, "uniformStride", stride);
                    set(state, "disabledFrameUniforms", new boolean[frameCount]);
                    set(state, "frameHardwareRayTracingActive", true);
                    set(state, "framePbrMaterialsActive", true);
                    set(state, "frameShadowActive", true);
                    VulkanRayTracingScene scene = (VulkanRayTracingScene)get(state, "rayTracingScene");
                    Class<?> frameType = field(scene, "frames").getType().getComponentType();
                    Object frames = java.lang.reflect.Array.newInstance(frameType, frameCount);
                    var constructor = frameType.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    for (int frame = 0; frame < frameCount; ++frame) {
                        primitives[frame] = MemoryUtil.memCalloc(64).order(ByteOrder.nativeOrder());
                        primitives[frame].putInt(0, 17 + frame);
                        Object slot = constructor.newInstance();
                        set(slot, "primitives", mappedBuffer(primitives[frame]));
                        set(slot, "enabled", true);
                        java.lang.reflect.Array.set(frames, frame, slot);
                    }
                    set(scene, "frames", frames);
                    set(scene, "ready", true);
                    runtime.device.rayTracing = VulkanRayTracingCapabilities.unavailable("host-only mapped scene");
                    set(runtime.device.rayTracing, "available", true);
                    VulkanRuntimeUpscalerServices upscalers = runtime.upscalerServices;
                    VulkanFrameRecordSubmitContext context = new VulkanFrameRecordSubmitContext(runtime,
                        upscalers.upscalerState, upscalers.upscalerPolicyOperations, upscalers.fsrAuxiliaryOperations);

                    assertEquals(enabled, context.capture().vulkanDynamicShadowsEnabled);
                    // No descriptor selection or preparation has run. A skipped/empty
                    // preparation must never reuse the preceding frame's hardware mode.
                    assertFalse(state.hardwareRayTracingActive());
                    VulkanColorDrawState draw = new VulkanColorDrawState();
                    VulkanColorDrawStateApplications.reset(draw, 0);
                    for (int purpose : new int[]{VulkanRenderPurpose.MAIN_WORLD, VulkanRenderPurpose.FIRST_PERSON}) {
                        draw.renderPurpose = purpose;
                        for (int mask : new int[]{7, 6, 1}) {
                            draw.colorMaskRed = (mask & 1) != 0;
                            draw.colorMaskGreen = (mask & 2) != 0;
                            draw.colorMaskBlue = (mask & 4) != 0;
                            assertFalse(VulkanColorPipelineSelection.usesHardwareWorldShader(draw,
                                state.hardwareRayTracingActive()));
                        }
                    }
                    for (int frame = 0; frame < frameCount; ++frame) {
                        boolean disabled = !enabled && frame == currentFrame;
                        assertEquals(disabled ? 0 : 17 + frame, primitives[frame].getInt(0));
                        if (disabled) {
                            assertEquals(0, uniforms.getFloat(frame * stride + 908 * 4), 0);
                            assertEquals(0, uniforms.getFloat(frame * stride + 911 * 4), 0);
                        } else {
                            for (int offset = frame * stride; offset < (frame + 1) * stride; offset += 4)
                                assertEquals("unprepared or in-flight frame is untouched", 0x5a5a5a5a,
                                    uniforms.getInt(offset));
                        }
                    }
                } finally {
                    MemoryUtil.memFree(uniforms);
                    for (ByteBuffer bytes : primitives) if (bytes != null) MemoryUtil.memFree(bytes);
                }
            }
        } finally {
            for (var entry : previous.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    public void configuredGiRemainsEnabledWithoutLocalEmittersAndOnlyWritesTheFencedFrame() throws Exception {
        VulkanRuntime runtime = new VulkanRuntime();
        VulkanColoredLightState state = new VulkanColoredLightState(runtime);
        ByteBuffer[] memory = new ByteBuffer[3];
        try {
            VulkanMappedBuffer[] buffers = new VulkanMappedBuffer[3];
            for (int frame = 0; frame < 3; ++frame) {
                memory[frame] = MemoryUtil.memCalloc(128 * 1024).order(ByteOrder.nativeOrder());
                memory[frame].putInt(12, 77);
                buffers[frame] = mappedBuffer(memory[frame]);
            }
            set(state,"buffers",buffers);
            set(state,"uploadedRevisions",new long[]{Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE});
            set(state,"enabledFrames",new boolean[3]);
            runtime.frameState.currentFrame=1;
            net.minecraft.src.world.core.LightweightTestWorld world =
                new net.minecraft.src.world.core.LightweightTestWorld("empty-gi-hardware-enable");
            assertTrue(state.prepareFrame(true,world,8,64,8,64,0,-64));
            assertEquals(VulkanColoredLightTiles.HEADER_SIZE,memory[1].getInt(12));
            assertEquals("empty atlas still enables hardware bounce",0,memory[1].getInt(28));
            assertEquals(77,memory[0].getInt(12)); assertEquals(77,memory[2].getInt(12));
            state.disableCurrentFrame();
            assertEquals(0,memory[1].getInt(12));
            assertEquals(77,memory[0].getInt(12)); assertEquals(77,memory[2].getInt(12));
        } finally {
            for (ByteBuffer bytes : memory) if (bytes != null) MemoryUtil.memFree(bytes);
        }
    }

    @Test
    public void hardwareModeAndEffectQualityUniformsAreIndependentAndFenced() throws Exception {
        Map<String,String> saved = new HashMap<>();
        String[] effects = {"dynamicShadows","emissiveShadowCasting","coloredGlobalIllumination","pbrMaterials",
            "screenSpaceWaterReflections","cloudShadows","waterSunGlint","lightShafts"};
        String[] qualities = {"low","medium","high","ultra"};
        String emissive = "minecraft.vulkan.emissiveShadowCasting.enabled";
        saved.put(emissive,System.getProperty(emissive));
        int expected = 0;
        for (int i=0;i<effects.length;++i) {
            String key="minecraft.vulkan."+effects[i]+".quality";
            saved.put(key,System.getProperty(key)); System.setProperty(key,qualities[i%4]);
            expected |= (i%4) << (i*2);
        }
        ByteBuffer bytes=MemoryUtil.memAlloc(16384*3).order(ByteOrder.nativeOrder());
        try {
            System.setProperty(emissive,"false");
            for(int offset=0;offset<bytes.capacity();offset+=4)bytes.putInt(offset,0x5a5a5a5a);
            VulkanDynamicShadowState state=new VulkanDynamicShadowState(new VulkanRuntime());
            set(state,"uniformBuffer",mappedBuffer(bytes)); set(state,"uniformStride",16384);
            set(state,"disabledFrameUniforms",new boolean[3]);
            set(state,"frameHardwareRayTracingActive",true); set(state,"framePbrMaterialsActive",false);
            Method write=VulkanDynamicShadowState.class.getDeclaredMethod("writeFrameUniform",int.class,boolean.class,float.class);
            write.setAccessible(true); write.invoke(state,1,true,.75f);
            assertEquals(0,bytes.getFloat(16384+908*4),0);
            assertEquals(0,bytes.getFloat(16384+909*4),0);
            assertEquals(expected,bytes.getFloat(16384+910*4),0);
            assertEquals(1,bytes.getFloat(16384+911*4),0);
            // A stale CPU active flag must not survive an explicitly disabled frame write.
            write.invoke(state,1,false,0);
            assertEquals(0,bytes.getFloat(16384+911*4),0);
            set(state,"frameHardwareRayTracingActive",false); set(state,"framePbrMaterialsActive",true);
            write.invoke(state,1,true,.75f);
            assertEquals(1,bytes.getFloat(16384+908*4),0);
            assertEquals(0,bytes.getFloat(16384+911*4),0);
            for(int offset=0;offset<16384;offset+=4)assertEquals(0x5a5a5a5a,bytes.getInt(offset));
            for(int offset=32768;offset<bytes.capacity();offset+=4)assertEquals(0x5a5a5a5a,bytes.getInt(offset));
        } finally {
            MemoryUtil.memFree(bytes);
            for(var item:saved.entrySet())if(item.getValue()==null)System.clearProperty(item.getKey());else System.setProperty(item.getKey(),item.getValue());
        }
    }

    @Test
    public void pbrOnlyCollectedLightsSerializeWithoutPointShadowAssignments() throws Exception {
        String property = "minecraft.vulkan.emissiveShadowCasting.enabled";
        String previous = System.getProperty(property);
        int stride = 16384;
        ByteBuffer memory = MemoryUtil.memAlloc(stride * 3).order(ByteOrder.nativeOrder());
        try {
            System.setProperty(property, "false");
            for (int offset = 0; offset < memory.capacity(); offset += 4) memory.putInt(offset, 0x5a5a5a5a);
            VulkanDynamicShadowState state = new VulkanDynamicShadowState(new VulkanRuntime());
            set(state, "uniformBuffer", mappedBuffer(memory));
            set(state, "uniformStride", stride);
            set(state, "disabledFrameUniforms", new boolean[3]);
            set(state, "framePbrMaterialsActive", true);
            set(state, "frameEmissiveLightCount", 2);
            VulkanEmissiveLight[] lights = (VulkanEmissiveLight[])get(state, "frameEmissiveLights");
            lights[0] = new VulkanEmissiveLight(1, 2, 3, 4, 12, 6, 1, 0.5f, 0.1f, 0.8f, 0.6f);
            lights[1] = new VulkanEmissiveLight(2, 5, 6, 7, 10, 4, 0.2f, 0.5f, 1, 0.4f, 0.3f);
            VulkanPointShadowFramePlan plan = VulkanPointShadowFramePlan.resolve(null, 0, 0, 0);
            assertEquals(0, plan.evaluatedLightCount());
            set(state, "framePointShadowPlan", plan);

            Method write = VulkanDynamicShadowState.class.getDeclaredMethod("writeFrameUniform", int.class, boolean.class, float.class);
            write.setAccessible(true);
            write.invoke(state, 1, true, 0.75f);
            assertEquals(1, memory.getFloat(stride + 84 * 4), 0);
            assertEquals(2, memory.getFloat(stride + 85 * 4), 0);
            assertEquals(2, memory.getFloat(stride + 108 * 4), 0);
            assertEquals(12, memory.getFloat(stride + 111 * 4), 0);
            assertEquals(1, memory.getFloat(stride + 364 * 4), 0);
            assertEquals(0.5f, memory.getFloat(stride + 365 * 4), 0);
            assertEquals(0.1f, memory.getFloat(stride + 366 * 4), 0);
            assertEquals(0.8f, memory.getFloat(stride + 367 * 4), 0);
            assertEquals(0.6f, memory.getFloat(stride + 620 * 4), 0);
            assertEquals(6, memory.getFloat(stride + 621 * 4), 0);
            assertEquals(1, memory.getFloat(stride + 622 * 4), 0);
            for (int light = 0; light < 64; ++light)
                assertEquals("point probe for light " + light, -1, memory.getFloat(stride + (623 + light * 4) * 4), 0);
            assertEquals(1, memory.getFloat(stride + 908 * 4), 0);
            assertEquals(0, memory.getFloat(stride + 909 * 4), 0);
            int palette = stride + VulkanEmissiveLightUniforms.LINEAR_COLOR_OFFSET_FLOATS * 4;
            assertEquals(VulkanEmissiveLightUniforms.linear(1), memory.getFloat(palette), 0);
            assertEquals(VulkanEmissiveLightUniforms.linear(0.5f), memory.getFloat(palette + 4), 0);
            assertEquals(VulkanEmissiveLightUniforms.linear(0.1f), memory.getFloat(palette + 8), 0);
            for (int light = 2; light < 64; ++light)
                for (int channel = 0; channel < 4; ++channel)
                    assertEquals("unused linear light", 0, memory.getFloat(palette + light * 16 + channel * 4), 0);
            for (int offset = 0; offset < stride; offset += 4)
                assertEquals("preceding in-flight frame", 0x5a5a5a5a, memory.getInt(offset));
            for (int offset = stride + VulkanEmissiveLightUniforms.SHADOW_UNIFORM_BYTES; offset < memory.capacity(); offset += 4)
                assertEquals("padding or subsequent in-flight frame", 0x5a5a5a5a, memory.getInt(offset));
        } finally {
            MemoryUtil.memFree(memory);
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    public void independentOptionsPreserveEachOthersVolumesAndOnlyDisableTheFencedFrame() throws Exception {
        Map<String, String> previous = new HashMap<>();
        for (String property : new String[]{PBR, GI, SUN}) previous.put(property, System.getProperty(property));
        try {
            // Keep the unrelated shadow UBO active so this test isolates the
            // two independent storage bindings selected by descriptorSet().
            System.setProperty(SUN, "true");
            for (boolean pbrEnabled : new boolean[]{false, true}) for (boolean giEnabled : new boolean[]{false, true}) {
                System.setProperty(PBR, Boolean.toString(pbrEnabled));
                System.setProperty(GI, Boolean.toString(giEnabled));
                try (Fixture fixture = new Fixture()) {
                    fixture.runtime.frameState.currentFrame = 1;
                    assertEquals(22L, fixture.state.descriptorSet());
                    if (pbrEnabled) assertSame(fixture.pbrVolume, get(fixture.pbrState, "volume"));
                    else assertNull(get(fixture.pbrState, "volume"));
                    if (giEnabled) assertSame(fixture.giVolume, get(fixture.giState, "volume"));
                    else assertNull(get(fixture.giState, "volume"));
                    assertHeaders(fixture.pbrMemory, 1, pbrEnabled, 1);
                    assertHeaders(fixture.giMemory, 1, giEnabled, 48);
                    fixture.runtime.frameState.currentFrame = 2;
                    assertEquals(33L, fixture.state.descriptorSet());
                    assertEquals(1, fixture.pbrMemory[0].getInt(12));
                    assertEquals(48, fixture.giMemory[0].getInt(12));
                    assertEquals(pbrEnabled ? 1 : 0, fixture.pbrMemory[2].getInt(12));
                    assertEquals(giEnabled ? 48 : 0, fixture.giMemory[2].getInt(12));
                }
            }
        } finally {
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void assertHeaders(ByteBuffer[] memory, int currentFrame, boolean enabled, int enabledValue) {
        for (int frame = 0; frame < memory.length; ++frame)
            assertEquals("header for frame " + frame, !enabled && frame == currentFrame ? 0 : enabledValue,
                memory[frame].getInt(12));
    }

    private static final class Fixture implements AutoCloseable {
        final VulkanRuntime runtime = new VulkanRuntime();
        final VulkanDynamicShadowState state = new VulkanDynamicShadowState(runtime);
        final VulkanPbrState pbrState;
        final VulkanColoredLightState giState;
        final VulkanPbrMaterialVolume pbrVolume = new VulkanPbrMaterialVolume();
        final VulkanColoredLightTiles giVolume = new VulkanColoredLightTiles();
        final ByteBuffer[] pbrMemory = new ByteBuffer[3];
        final ByteBuffer[] giMemory = new ByteBuffer[3];

        Fixture() throws Exception {
            set(state, "descriptorSets", new long[]{11, 22, 33});
            pbrState = (VulkanPbrState)get(state, "pbrState");
            giState = (VulkanColoredLightState)get(state, "coloredLightState");
            set(pbrState, "volume", pbrVolume);
            set(giState, "volume", giVolume);
            set(pbrState, "buffers", mappedFrames(pbrMemory, 1));
            set(giState, "buffers", mappedFrames(giMemory, 48));
            set(pbrState, "enabledFrames", new boolean[]{true, true, true});
            set(giState, "enabledFrames", new boolean[]{true, true, true});
        }

        @Override public void close() {
            // These are unmanaged host fixtures, never Vulkan allocations.
            for (ByteBuffer bytes : pbrMemory) if (bytes != null) MemoryUtil.memFree(bytes);
            for (ByteBuffer bytes : giMemory) if (bytes != null) MemoryUtil.memFree(bytes);
        }
    }

    private static VulkanMappedBuffer[] mappedFrames(ByteBuffer[] memory, int enabledValue) throws Exception {
        VulkanMappedBuffer[] buffers = new VulkanMappedBuffer[memory.length];
        for (int frame = 0; frame < memory.length; ++frame) {
            ByteBuffer bytes = MemoryUtil.memCalloc(64).order(ByteOrder.nativeOrder());
            memory[frame] = bytes;
            bytes.putInt(12, enabledValue);
            buffers[frame] = mappedBuffer(bytes);
        }
        return buffers;
    }

    private static VulkanMappedBuffer mappedBuffer(ByteBuffer bytes) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe)unsafeField.get(null);
        VulkanMappedBuffer mapped = (VulkanMappedBuffer)unsafe.allocateInstance(VulkanMappedBuffer.class);
        set(mapped, "allocation", new VulkanGpuBuffer(1L, 1L, bytes.capacity(), false, 0, bytes.capacity()));
        set(mapped, "mappedAddress", MemoryUtil.memAddress(bytes));
        return mapped;
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target, name).set(target, value);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
