package net.minecraft.src.graphics.backend.vulkan;

import java.util.Arrays;
import net.minecraft.src.block.Block;
import net.minecraft.src.world.core.LightweightTestWorld;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Explicit CPU timings: material transport preparation, not frame-rate measurements. */
public final class VulkanPbrVolumePerformanceProbe {
    private static volatile int sink;

    public static void main(String[] args) {
        LightweightTestWorld world = new LightweightTestWorld("pbr-volume-performance");
        for (int z=-2; z<=2; ++z) for (int x=-2; x<=2; ++x) {
            ChunkAccess chunk=world.prepareTestChunk(x,z); chunk.hasChunkData=true;
            for(int dz=0; dz<16; ++dz) for(int dx=0; dx<16; ++dx) {
                if(Boolean.getBoolean("pbr.probe.denseGround")) for(int y=40;y<62;++y)
                    chunk.setBlockIDWithMetadata(dx,y,dz,Block.stone.blockID,0);
                chunk.setBlockIDWithMetadata(dx,62,dz,Block.stone.blockID,0);
                chunk.setBlockIDWithMetadata(dx,64,dz,(dx+dz)%3==0?Block.stairSingle.blockID:Block.leaves.blockID,(dx+dz)%5);
                if(dx==0) for(int y=65;y<72;++y) chunk.setBlockIDWithMetadata(dx,y,dz,Block.stone.blockID,0);
            }
        }
        VulkanPbrMaterialVolume warmup = new VulkanPbrMaterialVolume();
        for (int i=0;i<200;++i) warmup.update(world,i%2==0?8:16,64,8);
        for (String fixture : new String[]{"fresh","block-edit","camera-step","cached-frame"}) {
            VulkanPbrMaterialVolume volume=new VulkanPbrMaterialVolume(); volume.update(world,8,64,8);
            double[] ms=new double[31];
            for(int sample=0;sample<ms.length;++sample) {
                if(fixture.equals("fresh")) volume=new VulkanPbrMaterialVolume();
                if(fixture.equals("block-edit")) world.getChunkForRenderSnapshot(0,0,false)
                    .setBlockIDWithMetadata(8,64,8,sample%2==0?Block.stone.blockID:Block.leaves.blockID,0);
                int frames=fixture.equals("cached-frame")?1000:1;
                long start=System.nanoTime();
                for(int i=0;i<frames;++i) volume.update(world,fixture.equals("camera-step")&&sample%2==0?16:8,64,8);
                ms[sample]=(System.nanoTime()-start)/1e6/frames;
                sink=Arrays.hashCode(volume.cells());
            }
            double[] warm=Arrays.copyOfRange(ms,12,ms.length);Arrays.sort(warm);
            System.out.printf("PBR volume fixture=%s medianMs=%.6f minMs=%.6f maxMs=%.6f outputHash=%d%n",
                fixture,warm[warm.length/2],warm[0],warm[warm.length-1],sink);
        }
    }
}
