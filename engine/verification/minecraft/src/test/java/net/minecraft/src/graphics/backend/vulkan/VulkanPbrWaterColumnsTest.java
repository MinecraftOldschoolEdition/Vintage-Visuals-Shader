package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.*;
import net.minecraft.src.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.src.world.chunk.ChunkProviderClient;
import net.minecraft.src.world.core.EnumSkyBlock;
import net.minecraft.src.world.core.LightweightTestWorld;
import org.junit.Test;

public class VulkanPbrWaterColumnsTest {
    @Test public void exposedColumnsExtendBeyondOpticalCubeWithoutLoadingChunks() {
        LightweightTestWorld world=new LightweightTestWorld("caustic-columns-range");
        ChunkAccess chunk=waterChunk(world,2,0,58,64);
        ChunkProviderClient provider=(ChunkProviderClient)world.getIChunkProvider();
        int count=provider.getLoadedChunkCount();
        VulkanPbrWaterColumns columns=new VulkanPbrWaterColumns();
        assertTrue(columns.update(world,7.999f,0));
        assertEquals((64<<16)|58,at(columns,36,4));
        assertEquals(0,at(columns,-40,4));
        assertEquals(count,provider.getLoadedChunkCount());
        assertTrue(columns.update(world,8.001f,0));
        assertEquals((64<<16)|58,at(columns,36,4));
        long revision=columns.revision();
        world.setWorldTime(18000);
        chunk.setLightValue(EnumSkyBlock.Block,4,60,4,12);
        assertTrue(columns.update(world,15.9f,0));
        assertEquals(revision,columns.revision());
    }

    @Test public void ceilingsDryColumnsAndEmbeddedSolidsCannotReceiveCaustics() {
        LightweightTestWorld world=new LightweightTestWorld("caustic-columns-occlusion");
        ChunkAccess chunk=waterChunk(world,0,0,30,64);
        // The bounded contiguous interval ends at32 cells; deeper receivers are excluded.
        VulkanPbrWaterColumns columns=new VulkanPbrWaterColumns();
        columns.update(world,0,0);
        assertEquals((64<<16)|32,at(columns,4,4));
        block(chunk,4,60,4,Block.stone.blockID);
        columns.update(world,0,0);
        assertEquals((64<<16)|61,at(columns,4,4));
        block(chunk,4,64,4,Block.stone.blockID);
        chunk.heightMap[(4<<4)|4]=65;
        columns.update(world,0,0);
        assertEquals(0,at(columns,4,4));
        assertEquals(0,at(columns,6,6));
    }

    @Test public void editedUnloadedReplacedAndDetachedChunksRefreshColumns() {
        LightweightTestWorld world=new LightweightTestWorld("caustic-columns-refresh");
        ChunkAccess chunk=waterChunk(world,0,0,58,64);
        VulkanPbrWaterColumns columns=new VulkanPbrWaterColumns();
        columns.update(world,0,0);
        long revision=columns.revision();
        block(chunk,4,63,4,0);chunk.heightMap[(4<<4)|4]=63;
        columns.update(world,0,0);
        assertTrue(columns.revision()>revision);
        assertEquals((63<<16)|58,at(columns,4,4));
        ((ChunkProviderClient)world.getIChunkProvider()).unloadChunk(0,0);
        columns.update(world,0,0);
        assertEquals(0,at(columns,4,4));
        waterChunk(world,0,0,55,65);columns.update(world,0,0);
        assertEquals((65<<16)|55,at(columns,4,4));
        assertFalse(columns.update(null,0,0));
        assertEquals(0,at(columns,4,4));
        revision=columns.revision();columns.clear();assertEquals(revision,columns.revision());
        assertFalse(columns.update(world,Float.NaN,0));
    }

    private static int at(VulkanPbrWaterColumns c,int x,int z) {
        return c.columns()[x-c.originX()+128*(z-c.originZ())];
    }
    private static ChunkAccess waterChunk(LightweightTestWorld world,int x,int z,int bottom,int surface) {
        ChunkAccess chunk=world.prepareTestChunk(x,z);chunk.hasChunkData=true;
        for(int y=bottom;y<surface;++y)block(chunk,4,y,4,Block.waterStill.blockID);
        chunk.heightMap[(4<<4)|4]=(byte)surface;
        return chunk;
    }
    private static void block(ChunkAccess chunk,int x,int y,int z,int id) {
        chunk.blocks[x<<11|z<<7|y]=(byte)id;
        chunk.setBlockMetadata(x,y,z,1);chunk.setBlockMetadata(x,y,z,0);
        chunk.rebuildNonEmptySectionSummary();
    }
}
