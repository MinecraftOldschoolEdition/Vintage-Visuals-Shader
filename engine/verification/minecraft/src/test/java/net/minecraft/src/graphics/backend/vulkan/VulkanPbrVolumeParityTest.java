package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.*;
import java.util.Random;
import net.minecraft.src.block.Block;
import net.minecraft.src.world.core.LightweightTestWorld;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.Test;

public class VulkanPbrVolumeParityTest {
    @Test public void rebuildRefreshesOpticalPropertiesAndPreservesEverySlabMetadata() {
        LightweightTestWorld world=new LightweightTestWorld("pbr-volume-encoding");
        ChunkAccess chunk=world.prepareTestChunk(0,0);chunk.hasChunkData=true;
        for(int metadata=0;metadata<16;++metadata) {
            chunk.setBlockIDWithMetadata(metadata,64,8,Block.stairSingle.blockID,metadata);
            chunk.setBlockIDWithMetadata(metadata,65,8,Block.stairDouble.blockID,metadata);
        }
        chunk.setBlockIDWithMetadata(8,66,8,Block.stone.blockID,0);
        VulkanPbrMaterialVolume actual=new VulkanPbrMaterialVolume();
        VulkanPbrMaterialVolumeReference expected=new VulkanPbrMaterialVolumeReference();
        actual.update(world,8,64,8);expected.update(world,8,64,8);
        assertArrayEquals(expected.cells(),actual.cells());
        int old=Block.lightOpacity[Block.stone.blockID];
        try {
            Block.lightOpacity[Block.stone.blockID]=1;
            chunk.setBlockMetadata(8,66,8,1);
            actual.update(world,8,64,8);expected.update(world,8,64,8);
            assertArrayEquals(expected.cells(),actual.cells());
        } finally {Block.lightOpacity[Block.stone.blockID]=old;}
    }

    @Test public void opticalWordsMatchFrozenVolumeAcrossEditsStepsAndMissingChunks() {
        LightweightTestWorld world=new LightweightTestWorld("pbr-volume-parity");
        Random random=new Random(918734);
        int[] blocks={0,Block.stone.blockID,Block.leaves.blockID,Block.stairSingle.blockID,
            Block.stairDouble.blockID,Block.waterStill.blockID,Block.glass.blockID,Block.sand.blockID,
            Block.blockSteel.blockID,Block.torchWood.blockID};
        for(int z=-2;z<=2;++z)for(int x=-2;x<=2;++x) {
            if(x==1&&z==-1)continue;
            ChunkAccess chunk=world.prepareTestChunk(x,z);chunk.hasChunkData=true;
            for(int i=0;i<900;++i) chunk.setBlockIDWithMetadata(random.nextInt(16),random.nextInt(128),
                random.nextInt(16),blocks[random.nextInt(blocks.length)],random.nextInt(16));
        }
        VulkanPbrMaterialVolume actual=new VulkanPbrMaterialVolume();
        VulkanPbrMaterialVolumeReference expected=new VulkanPbrMaterialVolumeReference();
        for(int step=0;step<32;++step) {
            ChunkAccess edited=world.getChunkForRenderSnapshot(0,0,false);
            edited.setBlockIDWithMetadata(8,64,8,blocks[step%blocks.length],step%16);
            if(step==12)world.prepareTestChunk(1,-1).hasChunkData=true;
            int x=new int[]{-24,-16,-8,0,8,16,24,8}[step%8];
            int y=new int[]{0,64,127,160}[step%4];
            assertEquals(expected.update(world,x,y,-8),actual.update(world,x,y,-8));
            assertEquals(expected.originX(),actual.originX());assertEquals(expected.originY(),actual.originY());
            assertEquals(expected.originZ(),actual.originZ());assertEquals(expected.revision(),actual.revision());
            assertArrayEquals("step="+step,expected.cells(),actual.cells());
            assertEquals(expected.update(world,x,y,-8),actual.update(world,x,y,-8));
            assertEquals(expected.revision(),actual.revision());
        }
        assertEquals(expected.update(null,0,0,0),actual.update(null,0,0,0));
        assertArrayEquals(expected.cells(),actual.cells());
    }
}
