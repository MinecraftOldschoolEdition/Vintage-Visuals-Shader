package net.minecraft.src.graphics.backend.vulkan;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import net.minecraft.src.block.Block;
import net.minecraft.src.world.core.LightweightTestWorld;

/** Explicit CPU benchmark; transport work and cached frames are measured separately. */
public final class VulkanGiTransportPerformanceProbe {
    private static volatile long sink;

    public static void main(String[] args) {
        for (int scene = 0; scene < 4; ++scene) transport(scene);
        tiles();
    }

    private static void transport(int scene) {
        int size = 48, count = size * size * size;
        byte[] opacity = new byte[count];
        int[] initial = new int[count], colors = new int[count], queue = new int[count];
        boolean[] queued = new boolean[count];
        Random random = new Random(0x9174L + scene);
        for (int i = 0; i < count; ++i) {
            if (scene == 2) opacity[i] = (byte) (random.nextInt(7) == 0 ? 15 : random.nextInt(4));
            if (scene == 3) opacity[i] = (byte) random.nextInt(16);
            if (scene == 1 ? i % 4 == 0 : random.nextInt(scene == 0 ? 4096 : 256) == 0)
                initial[i] = random.nextInt(0xffffff) + 1;
        }
        double[] times = new double[31];
        for (int run = 0; run < times.length; ++run) {
            System.arraycopy(initial, 0, colors, 0, count);
            long start = System.nanoTime();
            VulkanColoredLightVolume.diffuse(size, opacity, colors, queue, queued);
            times[run] = (System.nanoTime() - start) / 1e6;
            sink = Arrays.hashCode(colors);
        }
        report("diffuse-" + new String[]{"sparse-air", "dense-emitters", "mixed", "absorbing"}[scene], times, 12,
            "cells=" + count + " outputHash=" + sink);
    }

    private static void tiles() {
        LightweightTestWorld world = new LightweightTestWorld("gi-performance");
        for (int z = -2; z <= 2; ++z) for (int x = -2; x <= 2; ++x) {
            var chunk = world.prepareTestChunk(x, z);
            chunk.hasChunkData = true;
            for (int vz = 0; vz < 16; ++vz) for (int vx = 0; vx < 16; ++vx) {
                chunk.blocks[vx << 11 | vz << 7 | 62] = (byte) Block.stone.blockID;
                if ((vx + vz) % 11 == 0) chunk.blocks[vx << 11 | vz << 7 | 64] = (byte) Block.lavaStill.blockID;
            }
            chunk.rebuildNonEmptySectionSummary();
        }
        double[] times = new double[15];
        for (int run = 0; run < times.length; ++run) {
            VulkanColoredLightTiles tiles = new VulkanColoredLightTiles();
            long start = System.nanoTime();
            tiles.update(world, 8, 68, 8);
            times[run] = (System.nanoTime() - start) / 1e6;
            sink = Arrays.hashCode(tiles.words());
        }
        report("tile-first-build", times, 6, "outputHash=" + sink);
        VulkanColoredLightTiles tiles = new VulkanColoredLightTiles();
        tiles.update(world, 8, 68, 8);
        times = new double[21];
        for (int run = 0; run < times.length; ++run) {
            var chunk = world.getChunkForRenderSnapshot(0, 0, false);
            chunk.blocks[8 << 11 | 8 << 7 | 64] = (byte) (run % 2 == 0 ? Block.lavaStill.blockID : Block.stone.blockID);
            chunk.setBlockMetadata(8, 64, 8, run & 15);
            chunk.rebuildNonEmptySectionSummary();
            long start = System.nanoTime();
            tiles.update(world, 8, 68, 8);
            times[run] = (System.nanoTime() - start) / 1e6;
            sink = Arrays.hashCode(tiles.words());
        }
        report("tile-block-edit", times, 8, "outputHash=" + sink);
        times = new double[21];
        int builds = tiles.rebuildCount();
        for (int run = 0; run < times.length; ++run) {
            long start = System.nanoTime();
            for (int frame = 0; frame < 1000; ++frame) tiles.update(world, 8, 68, 8);
            times[run] = (System.nanoTime() - start) / 1e9;
        }
        if (builds != tiles.rebuildCount()) throw new AssertionError("Cached frames rebuilt transport");
        report("tile-cached-frame", times, 8, "rebuilds=0 (ms per frame)");
    }

    private static void report(String fixture, double[] times, int warmup, String extra) {
        double[] warmed = Arrays.copyOfRange(times, warmup, times.length);
        Arrays.sort(warmed);
        System.out.printf(Locale.ROOT, "GI CPU fixture=%s medianMs=%.6f minMs=%.6f p95Ms=%.6f %s%n",
            fixture, warmed[warmed.length / 2], warmed[0], warmed[(warmed.length * 95 / 100)], extra);
    }
}
