package net.minecraft.src.graphics.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;

/** Native differential check of parallel ray scheduling against the frozen serial loop. */
public final class VulkanHardwareIrradianceParallelGpuProbe {
    public static void main(String[] args) throws Exception {
        VulkanRayTracingGpuProbe gpu = new VulkanRayTracingGpuProbe();
        int compared = 0;
        try {
            gpu.initialize();
            String candidate = VulkanHardwareIrradianceGpuProbe.computeSource();
            String serial;
            try (var stream = VulkanHardwareIrradianceParallelGpuProbe.class.getResourceAsStream("/shaders/hardware_irradiance_serial_reference.glsl")) {
                if (stream == null) throw new IllegalStateException("Missing frozen serial compute loop");
                serial = candidate.substring(0, candidate.indexOf("// Two probes share a group"))
                    + new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (int scene = 0; scene < 6; ++scene) {
                int primitives = scene == 0 ? 1 : 128;
                float[] boxes = new float[primitives * 6];
                int[] materials = new int[primitives * 8];
                for (int i = 0; i < primitives; ++i) {
                    int x = i % 8 - 4, z = i / 8 % 8 - 4, y = i / 64 * 4 - 1;
                    if (scene == 0) x = y = z = 1000;
                    int at = i * 6;
                    boxes[at] = x; boxes[at + 1] = y; boxes[at + 2] = z;
                    boxes[at + 3] = x + 1; boxes[at + 4] = y + 1; boxes[at + 5] = z + 1;
                    at = i * 8;
                    Arrays.fill(materials, at, at + 6, scene == 3 && i % 3 != 0 ? 0 : 1);
                    materials[at + 6] = 0xffa051;
                    materials[at + 7] = switch (scene) {
                        case 2 -> 196 | 8192;
                        case 4 -> 224 | 131072;
                        case 5 -> 203 | 15 << 8 | 4096;
                        default -> 192 | 4096;
                    };
                }
                // Prepare AS geometry while probe updates are disabled.
                gpu.dispatch(0, boxes, materials, primitives, scene + 1, true, false);
                for (int quality = 0; quality < 4; ++quality) for (int count : new int[]{1, 2, 3, 31, 64, 65, 257}) {
                    final int tier = quality;
                    gpu.globalShadowData.write(0, VulkanRayTracingGpuProbe.GLOBAL_SHADOW_BYTES, bytes -> {
                        bytes.order(ByteOrder.nativeOrder()); while (bytes.hasRemaining()) bytes.put((byte) 0);
                        bytes.putFloat(348, tier % 2 == 0 ? 0 : 1);
                        bytes.putFloat(368, .3f).putFloat(372, 1).putFloat(376, .1f);
                        bytes.putFloat(3644, 1);
                    });
                    gpu.materialData.write(16, 32, bytes -> bytes.order(ByteOrder.nativeOrder())
                        .putFloat(.1f).putFloat(.2f).putFloat(.4f).putFloat(1)
                        .putFloat(.1f).putFloat(.2f).putFloat(.4f).putFloat(0));
                    int updates = count == 1 ? 1 : count - 1;
                    int[] expected = null;
                    for (int variant = 0; variant < 2; ++variant) {
                        gpu.replaceProgram(variant == 0 ? serial : candidate);
                        gpu.irradianceData.write(0, VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES, bytes -> {
                            bytes.order(ByteOrder.nativeOrder()); while (bytes.hasRemaining()) bytes.put((byte) 0);
                            bytes.putInt(0, count).putInt(4, 1).putInt(8, count - 1).putInt(12, updates);
                            bytes.putInt(44, VulkanHardwareIrradianceVolume.rayCountFor(tier));
                            bytes.putInt(48, 64).putInt(52, -32).putInt(56, -128);
                            for (int probe = 0; probe < count; ++probe) {
                                int at = (VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS + probe * 28) * 4;
                                bytes.putFloat(at, probe % 9 - 4 + .37f).putFloat(at + 4, (probe / 9 % 4) + .5f)
                                    .putFloat(at + 8, probe / 36 - 3 + .61f).putFloat(at + 12, probe % 7 == 0 && count > 1 ? -1 : 0);
                                // Sentinel payload proves invalid and unselected probes are untouched.
                                for (int channel = 4; channel < 28; ++channel) bytes.putFloat(at + channel * 4, -.25f);
                            }
                        });
                        int groups = variant == 0 ? (updates + 63) / 64 : VulkanHardwareIrradianceShader.workgroupCount(updates);
                        gpu.dispatch(0, null, null, 0, 0, false, false, groups);
                        var data = MemoryUtil.memByteBuffer(gpu.irradianceData.mappedAddress(), VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES)
                            .order(ByteOrder.nativeOrder());
                        int[] actual = new int[count * 28];
                        data.position(VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS * 4); data.asIntBuffer().get(actual);
                        if (variant == 0) expected = actual;
                        else for (int word = 0; word < actual.length; ++word) {
                            if (actual[word] != expected[word]) throw new AssertionError("SH/AO changed: scene=" + scene
                                + " quality=" + quality + " count=" + count + " word=" + word + " expected="
                                + Float.intBitsToFloat(expected[word]) + " actual=" + Float.intBitsToFloat(actual[word]));
                            ++compared;
                        }
                    }
                    // Avoid tracing with the previous case's metadata during AS preparation.
                    gpu.irradianceData.write(4, 4, bytes -> bytes.putInt(0));
                }
            }
            System.out.println("Parallel irradiance GPU PASS: " + compared
                + " exact SH/AO/position words; open, opaque, foliage, cutout, fluid, emissive; all qualities; invalid probes, cursor wrap, partial batches");
        } finally { gpu.close(); }
    }
}
