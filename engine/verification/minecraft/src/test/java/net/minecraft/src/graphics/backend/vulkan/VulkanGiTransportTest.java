package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.*;
import java.util.Random;
import org.junit.Test;

public class VulkanGiTransportTest {
    @Test
    public void matchesFrozenScalarTransportAcrossAbsorptionAndQueueWraps() {
        Random random = new Random(20260930);
        for (int size : new int[]{1, 2, 5, 16, 48}) for (int scene = 0; scene < 12; ++scene) {
            int count = size * size * size;
            byte[] opacity = new byte[count];
            int[] expected = new int[count];
            for (int cell = 0; cell < count; ++cell) {
                opacity[cell] = (byte) (scene % 3 == 0 ? 0 : random.nextInt(256));
                if (scene < 3 || random.nextInt(16) == 0) expected[cell] = random.nextInt(0x1000000);
            }
            int[] actual = expected.clone(), queue = new int[count];
            boolean[] queued = new boolean[count];
            VulkanGiTransportReference.diffuse(size, opacity, expected);
            VulkanColoredLightVolume.diffuse(size, opacity, actual, queue, queued);
            assertArrayEquals("size=" + size + " scene=" + scene, expected, actual);
            for (boolean pending : queued) assertFalse("Transport must drain reusable queue", pending);
            // Reusing a queue on an already converged field must remain exact.
            VulkanColoredLightVolume.diffuse(size, opacity, actual, queue, queued);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void everyChannelAndTransmittingOpacityRetainsIntegerDecay() {
        int size = 2;
        for (int opacity = 0; opacity < 16; ++opacity) for (int value = 0; value < 256; ++value) {
            byte[] material = new byte[8];
            java.util.Arrays.fill(material, (byte) 15);
            material[1] = (byte) opacity;
            int[] expected = new int[8];
            expected[0] = value | (255 - value) << 8 | (value * 37 & 255) << 16;
            int[] actual = expected.clone();
            VulkanGiTransportReference.diffuse(size, material, expected);
            VulkanColoredLightVolume.diffuse(size, material, actual);
            assertArrayEquals("opacity=" + opacity + " value=" + value, expected, actual);
        }
    }
}
