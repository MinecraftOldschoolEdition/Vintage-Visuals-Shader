package net.minecraft.src.graphics.backend.vulkan;

import java.util.Arrays;

/** Frozen September 30 transport oracle. Keep independent of production optimizations. */
final class VulkanGiTransportReference {
    /** RGB8, with red in the low byte, laid out as x + size * (z + size * y). */
    static void diffuse(int size, byte[] opacity, int[] colors) {
        int count = size * size * size;
        if (size <= 0 || opacity.length != count || colors.length != count) {
            throw new IllegalArgumentException("Voxel arrays must match the volume dimensions");
        }
        diffuse(size, opacity, colors, new int[count], new boolean[count]);
    }

    static void diffuse(int size, byte[] opacity, int[] colors, int[] queue, boolean[] queued) {
        int count = colors.length;
        int head = 0;
        int tail = 0;
        int pending = 0;
        Arrays.fill(queued, false);
        for (int cell = 0; cell < count; ++cell) {
            if (colors[cell] != 0) {
                queue[tail] = cell;
                tail = (tail + 1) % count;
                queued[cell] = true;
                ++pending;
            }
        }
        int layer = size * size;
        while (pending > 0) {
            int cell = queue[head];
            head = (head + 1) % count;
            --pending;
            queued[cell] = false;
            int x = cell % size;
            int z = (cell / size) % size;
            int y = cell / layer;
            int source = colors[cell];
            for (int face = 0; face < 6; ++face) {
                int neighbor;
                switch (face) {
                    case 0: if (x == 0) continue; neighbor = cell - 1; break;
                    case 1: if (x + 1 == size) continue; neighbor = cell + 1; break;
                    case 2: if (z == 0) continue; neighbor = cell - size; break;
                    case 3: if (z + 1 == size) continue; neighbor = cell + size; break;
                    case 4: if (y == 0) continue; neighbor = cell - layer; break;
                    default: if (y + 1 == size) continue; neighbor = cell + layer; break;
                }
                int absorption = opacity[neighbor] & 255;
                if (absorption >= 15) continue;
                int transported = attenuate(source, Math.max(1, absorption));
                int previous = colors[neighbor];
                int mixed = Math.max(previous & 255, transported & 255)
                    | Math.max(previous >> 8 & 255, transported >> 8 & 255) << 8
                    | Math.max(previous >> 16 & 255, transported >> 16 & 255) << 16;
                if (mixed != previous) {
                    colors[neighbor] = mixed;
                    if (!queued[neighbor]) {
                        queue[tail] = neighbor;
                        tail = (tail + 1) % count;
                        queued[neighbor] = true;
                        ++pending;
                    }
                }
            }
        }
    }

    private static int attenuate(int color, int distance) {
        int red = color & 255;
        int green = color >> 8 & 255;
        int blue = color >> 16 & 255;
        for (int step = 0; step < distance; ++step) {
            // Proportional decay transports the emitter hue around open corners.
            // The integer loss makes even a full-strength source vanish by step 15.
            red = Math.max(0, red * 3 / 4 - 1);
            green = Math.max(0, green * 3 / 4 - 1);
            blue = Math.max(0, blue * 3 / 4 - 1);
        }
        return red | green << 8 | blue << 16;
    }
}
