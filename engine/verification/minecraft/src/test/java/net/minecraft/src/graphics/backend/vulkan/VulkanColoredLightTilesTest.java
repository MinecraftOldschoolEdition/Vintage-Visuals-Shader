package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.*;

import net.minecraft.src.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.src.world.core.LightweightTestWorld;
import org.junit.Test;

public class VulkanColoredLightTilesTest {
    @Test
    public void opticalRunExpansionPreservesBottomTopAndMissingColumnHalos() {
        LightweightTestWorld world = world("optical-run-edges", -1, 1, -1, 1);
        for (int sourceY : new int[]{0, world.getMaxBuildHeight() - 1}) {
            block(world, 15, sourceY, 15, Block.lavaStill.blockID);
            VulkanColoredLightTiles tiles = new VulkanColoredLightTiles();
            VulkanColoredLightVolume dense = new VulkanColoredLightVolume();
            tiles.update(world, 16, sourceY, 16);
            dense.update(world, 16, sourceY, 16);
            for (int y = sourceY - 8; y <= sourceY + 8; ++y)
                for (int z = 12; z < 24; ++z) for (int x = 12; x < 24; ++x) {
                    int at = x - dense.originX() + 48 * (z - dense.originZ() + 48 * (y - dense.originY()));
                    assertEquals("optical halo at " + x + "," + y + "," + z, dense.colors()[at], color(tiles, x, y, z));
                }
            block(world, 15, sourceY, 15, 0);
        }
    }

    @Test
    public void largeLavaFieldMetadataTicksDoNotRebuildUnchangedLightTransport() {
        LightweightTestWorld world = world("large-lava-ticks", -1, 2, -1, 2);
        for (int z = 0; z < 32; ++z) for (int x = 0; x < 32; ++x)
            block(world, x, 64, z, Block.lavaStill.blockID);
        VulkanColoredLightTiles tiles = new VulkanColoredLightTiles();
        tiles.update(world, 8, 68, 8);
        int builds = tiles.rebuildCount();
        long revision = tiles.revision();
        int[] words = tiles.words();
        long started = System.nanoTime();
        for (int tick = 0; tick < 4; ++tick) {
            for (int z = 0; z < 32; z += 16) for (int x = 0; x < 32; x += 16)
                world.getChunkForRenderSnapshot(x >> 4, z >> 4, false).setBlockMetadata(0, 64, 0, tick + 2);
            tiles.update(world, 8, 68, 8);
        }
        System.out.println("Dense unchanged lava updates ms=" + (System.nanoTime() - started) / 1e6
            + " transport rebuilds=" + (tiles.rebuildCount() - builds));
        assertEquals(revision, tiles.revision());
        assertSame(words, tiles.words());
        assertEquals("Fluid levels must not rerun unchanged colored-light transport", builds, tiles.rebuildCount());
        block(world, 0, 64, 0, Block.lavaMoving.blockID);
        tiles.update(world, 8, 68, 8);
        assertEquals("Flowing/still IDs with identical emission and opacity share transport", builds, tiles.rebuildCount());
        assertSame(words, tiles.words());
        block(world, 0, 64, 0, Block.stone.blockID);
        tiles.update(world, 8, 68, 8);
        assertTrue("A real opacity/emission change must still rebuild", tiles.rebuildCount() > builds);
        VulkanColoredLightTiles fresh = new VulkanColoredLightTiles();
        fresh.update(world, 8, 68, 8);
        assertArrayEquals(fresh.words(), tiles.words());
    }

    @Test
    public void aDistantVisibleEmitterKeepsLightingItsWorldNeighborsAcrossCameraAnchors() {
        LightweightTestWorld world = world("tile-far", -2, 4, -2, 2);
        block(world, 40, 64, 8, Block.lavaStill.blockID);
        VulkanColoredLightVolume oldVolume = new VulkanColoredLightVolume();
        assertFalse("source lies outside the former camera-centered cube", oldVolume.update(world, 0,64,8));
        VulkanColoredLightTiles tiles = new VulkanColoredLightTiles();
        assertTrue(tiles.update(world,0,64,8));
        int nearby = color(tiles,41,64,8);
        assertTrue(nearby != 0);
        for (float[] eye : new float[][]{{8,64,8},{-8,64,8},{16,64,-16},{0,92,8}}) {
            assertTrue(tiles.update(world,eye[0],eye[1],eye[2]));
            assertEquals("camera motion/diagonal view cannot fade a resident light",nearby,color(tiles,41,64,8));
        }
    }

    @Test
    public void neighboringTilesMatchTheSameUnbrokenVoxelTransportField() {
        LightweightTestWorld world = world("tile-seams",-2,2,-2,2);
        block(world,15,64,15,Block.lavaStill.blockID);
        VulkanColoredLightTiles tiles = new VulkanColoredLightTiles();
        assertTrue(tiles.update(world,8,64,8));
        VulkanColoredLightVolume dense = new VulkanColoredLightVolume();
        assertTrue(dense.update(world,16,64,16));
        for(int y=60;y<=68;y++) for(int z=12;z<=19;z++) for(int x=12;x<=19;x++) {
            int index=x-dense.originX()+48*(z-dense.originZ()+48*(y-dense.originY()));
            assertEquals("tile seam at "+x+","+y+","+z,dense.colors()[index],color(tiles,x,y,z));
        }
    }

    @Test
    public void negativeCoordinatesAndFormerCubeEdgesHaveNoCameraFade() {
        LightweightTestWorld world = world("tile-negative",-5,1,-5,1);
        block(world,-33,64,-17,Block.lavaStill.blockID);
        VulkanColoredLightTiles tiles = new VulkanColoredLightTiles();
        tiles.update(world,-48,64,-32);
        int expected=color(tiles,-32,64,-17);
        assertTrue(expected!=0);
        for(int x:new int[]{-64,-48,-32,-16,0,16}) {
            tiles.update(world,x,64,-32);
            assertEquals(expected,color(tiles,-32,64,-17));
        }
    }

    @Test
    public void stableFramesAndCameraStepsReuseTransportWhileEditsInvalidateNeighbors() {
        LightweightTestWorld world=world("tile-cache",-2,2,-2,2);
        block(world,15,64,8,Block.lavaStill.blockID);
        VulkanColoredLightTiles tiles=new VulkanColoredLightTiles();
        tiles.update(world,8,64,8);
        int builds=tiles.rebuildCount(); long revision=tiles.revision(); int[] payload=tiles.words();
        long start=System.nanoTime();
        for(int frame=0;frame<1000;frame++) tiles.update(world,8+(frame%7),64,8);
        System.out.println("Tiled GI cached frame average ms="+(System.nanoTime()-start)/1e9);
        assertEquals(builds,tiles.rebuildCount()); assertEquals(revision,tiles.revision()); assertSame(payload,tiles.words());
        tiles.update(world,16,64,8);
        assertEquals("world-aligned transport is reusable after camera anchor shift",builds,tiles.rebuildCount());
        assertTrue(color(tiles,17,64,8)!=0);
        for(int z=-16;z<32;z++) for(int y=48;y<81;y++) block(world,16,y,z,Block.stone.blockID);
        tiles.update(world,16,64,8);
        assertTrue(tiles.rebuildCount()>builds);
        assertEquals("opaque wall must stop transport across a tile seam",0,color(tiles,17,64,8));
        block(world,16,64,8,0);
        tiles.update(world,16,64,8);
        assertTrue(color(tiles,17,64,8)!=0);
        block(world,15,64,8,0);
        assertFalse(tiles.update(world,16,64,8));
        assertEquals(0,color(tiles,17,64,8));
    }

    @Test
    public void missingChunksAreOpaqueAndArrivalAndWorldChangesRefreshTheField() {
        LightweightTestWorld world=world("tile-arrival",0,0,0,0);
        block(world,15,64,8,Block.lavaStill.blockID);
        VulkanColoredLightTiles tiles=new VulkanColoredLightTiles();
        assertTrue(tiles.update(world,8,64,8));
        assertEquals(0,color(tiles,16,64,8));
        ChunkAccess arrival=world.prepareTestChunk(1,0); arrival.hasChunkData=true;
        tiles.update(world,8,64,8); assertTrue(color(tiles,16,64,8)!=0);
        assertFalse(tiles.update(world("tile-other",0,1,0,0),8,64,8));
        assertEquals(0,color(tiles,16,64,8));
        assertFalse(tiles.update(null,8,64,8));
    }

    @Test
    public void torchRadianceIsSubtleWithoutChangingLavaAndPayloadIsBounded() {
        int torch=VulkanColoredLightVolume.emissionColor(Block.torchWood.blockID,0);
        assertEquals(143,torch&255); assertEquals(143,(torch>>8)&255); assertEquals(114,(torch>>16)&255);
        assertEquals(255,VulkanColoredLightVolume.emissionColor(Block.lavaStill.blockID,0)&255);
        assertTrue(VulkanColoredLightState.STORAGE_BYTES<128*1024*1024);
    }

    static int color(VulkanColoredLightTiles tiles,int x,int y,int z) {
        int[] words=tiles.words(); if(words[3]==0) return 0;
        x-=tiles.originX(); y-=tiles.originY(); z-=tiles.originZ();
        if(x<0||y<0||z<0||x>=words[0]*16||y>=words[1]*16||z>=words[2]*16) return 0;
        int offset=words[4+(x>>4)+words[0]*((z>>4)+words[2]*(y>>4))];
        return offset==0 ? 0 : words[offset+(x&15)+16*((z&15)+16*(y&15))]&0xffffff;
    }

    private static LightweightTestWorld world(String name,int minX,int maxX,int minZ,int maxZ) {
        LightweightTestWorld world=new LightweightTestWorld(name);
        for(int z=minZ;z<=maxZ;z++) for(int x=minX;x<=maxX;x++) world.prepareTestChunk(x,z).hasChunkData=true;
        return world;
    }
    private static void block(LightweightTestWorld world,int x,int y,int z,int block) {
        ChunkAccess chunk=world.getChunkForRenderSnapshot(x>>4,z>>4,false);
        chunk.blocks[(x&15)<<11|(z&15)<<7|y]=(byte)block;
        chunk.setBlockMetadata(x&15,y,z&15,(chunk.getBlockMetadata(x&15,y,z&15)+1)&15);
        chunk.rebuildNonEmptySectionSummary();
    }
}
