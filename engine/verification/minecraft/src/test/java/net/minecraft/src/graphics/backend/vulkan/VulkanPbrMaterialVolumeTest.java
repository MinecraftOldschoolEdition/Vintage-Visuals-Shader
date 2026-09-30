package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.src.block.Block;
import net.minecraft.src.graphics.api.TerrainMaterial;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.src.world.chunk.ChunkProviderClient;
import net.minecraft.src.world.core.EnumSkyBlock;
import net.minecraft.src.world.core.LightweightTestWorld;
import org.junit.Test;

public class VulkanPbrMaterialVolumeTest {
    @Test
    public void cellsSeparateAirOpaqueWallsAndOpticallyThinLeaves() {
        assertEquals(0, VulkanPbrMaterialVolume.encode(0, 0));
        assertEquals(TerrainMaterial.STONE_ALPHA | 15 << 8 | VulkanPbrMaterialVolume.OPAQUE_BIT,
            VulkanPbrMaterialVolume.encode(Block.stone.blockID, 0));
        assertEquals(TerrainMaterial.LEAVES_ALPHA | 1 << 8,
            VulkanPbrMaterialVolume.encode(Block.leaves.blockID, 4));
        assertEquals(TerrainMaterial.GLASS_ALPHA, VulkanPbrMaterialVolume.encode(Block.glass.blockID, 0));
        assertEquals(TerrainMaterial.WOOD_ALPHA,
            VulkanPbrMaterialVolume.encode(Block.stairSingle.blockID, 2) & 255);
    }

    @Test
    public void snapshotPreservesMaterialThicknessAndWorldAxisAddressing() {
        LightweightTestWorld world = new LightweightTestWorld("pbr-voxel-materials");
        ChunkAccess chunk = renderableChunk(world, 0, 0);
        for (int z = 7; z <= 9; ++z) setBlock(chunk, 8, 64, z, Block.leaves.blockID, 4);
        setBlock(chunk, 9, 65, 10, Block.stone.blockID, 0);
        setBlock(chunk, 7, 66, 11, Block.blockGold.blockID, 0);
        VulkanPbrMaterialVolume volume = new VulkanPbrMaterialVolume();
        assertTrue(volume.update(world, 8, 64, 8));
        for (int z = 7; z <= 9; ++z)
            assertEquals(TerrainMaterial.LEAVES_ALPHA | 1 << 8, at(volume, 8, 64, z));
        assertEquals(0, at(volume, 8, 64, 6));
        assertEquals(0, at(volume, 8, 64, 10));
        assertEquals(TerrainMaterial.STONE_ALPHA, at(volume, 9, 65, 10) & 255);
        assertEquals(TerrainMaterial.GOLD_ALPHA, at(volume, 7, 66, 11) & 255);
    }

    @Test
    public void volumeNeverLoadsMissingChunksAndRefreshesWhenTheyArrive() {
        LightweightTestWorld world = new LightweightTestWorld("pbr-voxel-arrival");
        renderableChunk(world, 0, 0);
        ChunkProviderClient provider = (ChunkProviderClient)world.getIChunkProvider();
        int countBefore = provider.getLoadedChunkCount();
        VulkanPbrMaterialVolume volume = new VulkanPbrMaterialVolume();
        assertTrue(volume.update(world, 16, 64, 8));
        assertEquals(countBefore, provider.getLoadedChunkCount());
        assertEquals(VulkanPbrMaterialVolume.UNLOADED_CELL, at(volume, 16, 64, 8));
        long revision = volume.revision();
        renderableChunk(world, 1, 0);
        assertTrue(volume.update(world, 16, 64, 8));
        assertTrue(volume.revision() > revision);
        assertEquals(0, at(volume, 16, 64, 8));
    }

    @Test
    public void lightingTimeAndSubcellMovementDoNotRebuildMaterialCells() {
        LightweightTestWorld world = new LightweightTestWorld("pbr-voxel-cache");
        ChunkAccess chunk = renderableChunk(world, 0, 0);
        setBlock(chunk, 8, 64, 8, Block.leaves.blockID, 4);
        VulkanPbrMaterialVolume volume = new VulkanPbrMaterialVolume();
        assertTrue(volume.update(world, 8.1f, 64.1f, 8.1f));
        long revision = volume.revision();
        chunk.setLightValue(EnumSkyBlock.Block, 8, 64, 8, 12);
        world.setWorldTime(18000);
        for (int frame = 0; frame < 100; ++frame) assertTrue(volume.update(world, 15.9f, 71.9f, 15.9f));
        assertEquals(revision, volume.revision());
        setBlock(chunk, 8, 64, 8, Block.stone.blockID, 0);
        assertTrue(volume.update(world, 15.9f, 71.9f, 15.9f));
        assertTrue(volume.revision() > revision);
        assertEquals(TerrainMaterial.STONE_ALPHA, at(volume, 8, 64, 8) & 255);
    }

    @Test
    public void metadataChangesRetagCellsAndReplacedChunksCannotReuseOldMaterials() {
        LightweightTestWorld world = new LightweightTestWorld("pbr-voxel-identities");
        ChunkAccess chunk = renderableChunk(world, 0, 0);
        setBlock(chunk, 8, 64, 8, Block.stairSingle.blockID, 0);
        VulkanPbrMaterialVolume volume = new VulkanPbrMaterialVolume();
        volume.update(world, 8, 64, 8);
        long revision = volume.revision();
        chunk.setBlockMetadata(8, 64, 8, 2);
        volume.update(world, 8, 64, 8);
        assertTrue(volume.revision() > revision);
        assertEquals(TerrainMaterial.WOOD_ALPHA, at(volume, 8, 64, 8) & 255);
        ((ChunkProviderClient)world.getIChunkProvider()).unloadChunk(0, 0);
        volume.update(world, 8, 64, 8);
        assertEquals(VulkanPbrMaterialVolume.UNLOADED_CELL, at(volume, 8, 64, 8));
        revision = volume.revision();
        renderableChunk(world, 0, 0);
        volume.update(world, 8, 64, 8);
        assertTrue(volume.revision() > revision);
        assertEquals(0, at(volume, 8, 64, 8));
    }

    @Test
    public void changingWorldOrDetachingCannotKeepOldOccluders() {
        LightweightTestWorld first = new LightweightTestWorld("pbr-voxel-first");
        setBlock(renderableChunk(first, 0, 0), 8, 64, 8, Block.stone.blockID, 0);
        LightweightTestWorld second = new LightweightTestWorld("pbr-voxel-second");
        renderableChunk(second, 0, 0);
        VulkanPbrMaterialVolume volume = new VulkanPbrMaterialVolume();
        volume.update(first, 8, 64, 8);
        volume.update(second, 8, 64, 8);
        assertEquals(0, at(volume, 8, 64, 8));
        assertFalse(volume.update(null, 8, 64, 8));
        long revision = volume.revision();
        volume.clear();
        assertEquals(revision, volume.revision());
        assertFalse(volume.update(first, Float.NaN, 64, 8));
    }

    @Test
    public void aboveWorldIsOpenAndBelowWorldIsClosedInLoadedColumns() {
        LightweightTestWorld world = new LightweightTestWorld("pbr-voxel-heights");
        renderableChunk(world, 0, 0);
        VulkanPbrMaterialVolume volume = new VulkanPbrMaterialVolume();
        volume.update(world, 8, world.getMinBuildHeight(), 8);
        assertEquals(VulkanPbrMaterialVolume.UNLOADED_CELL, at(volume, 8, world.getMinBuildHeight() - 1, 8));
        volume.update(world, 8, world.getMaxBuildHeight(), 8);
        assertEquals(0, at(volume, 8, world.getMaxBuildHeight(), 8));
    }

    @Test
    public void anchorMovesInEightBlockStepsIncludingFarAndNegativeCoordinates() {
        assertEquals(-24, VulkanPbrMaterialVolume.originFor(0.1f));
        assertEquals(-24, VulkanPbrMaterialVolume.originFor(7.9f));
        assertEquals(-16, VulkanPbrMaterialVolume.originFor(8));
        assertEquals(-32, VulkanPbrMaterialVolume.originFor(-0.1f));
        assertEquals(29999976, VulkanPbrMaterialVolume.originFor(30000000));
    }

    @Test
    public void skipMetadataNeverCoversMaterialOrUnloadedCellsAndRefreshesOnEdits() {
        int[] cells = new int[VulkanPbrMaterialVolume.CELL_COUNT];
        int size = VulkanPbrMaterialVolume.SIZE;
        int[] materials = {VulkanPbrMaterialVolume.UNLOADED_CELL,
            VulkanPbrMaterialVolume.encode(Block.leaves.blockID, 0),
            VulkanPbrMaterialVolume.encode(Block.glass.blockID, 0),
            VulkanPbrMaterialVolume.encode(Block.waterStill.blockID, 0)};
        for (int i = 0; i < materials.length; ++i) cells[i * 4 + 3 + size * (3 + size * 3)] = materials[i];
        VulkanPbrMaterialVolume.markEmptyBricks(cells);
        for (int y = 0; y < size; ++y) for (int z = 0; z < size; ++z) for (int x = 0; x < size; ++x) {
            boolean occupiedBrick = y < 4 && z < 4 && x < 16;
            assertEquals(occupiedBrick ? 0 : VulkanPbrMaterialVolume.EMPTY_BRICK_BIT,
                cells[x + size * (z + size * y)] & VulkanPbrMaterialVolume.EMPTY_BRICK_BIT);
        }
        cells[20] = VulkanPbrMaterialVolume.OPAQUE_BIT;
        cells[3 + size * (3 + size * 3)] = 0;
        VulkanPbrMaterialVolume.markEmptyBricks(cells);
        assertEquals(VulkanPbrMaterialVolume.EMPTY_BRICK_BIT, cells[0]);
        assertEquals(0, cells[21]);
        assertEquals(VulkanPbrMaterialVolume.OPAQUE_BIT, cells[20]);
    }

    private static int at(VulkanPbrMaterialVolume volume, int x, int y, int z) {
        int localX = x - volume.originX();
        int localY = y - volume.originY();
        int localZ = z - volume.originZ();
        return volume.cells()[localX + VulkanPbrMaterialVolume.SIZE * (localZ + VulkanPbrMaterialVolume.SIZE * localY)]
            & ~VulkanPbrMaterialVolume.EMPTY_BRICK_BIT;
    }

    private static ChunkAccess renderableChunk(LightweightTestWorld world, int x, int z) {
        ChunkAccess chunk = world.prepareTestChunk(x, z);
        chunk.hasChunkData = true;
        return chunk;
    }

    private static void setBlock(ChunkAccess chunk, int x, int y, int z, int id, int metadata) {
        chunk.blocks[x << 11 | z << 7 | y] = (byte)id;
        // Mutate through the metadata revision channel without gameplay physics.
        chunk.setBlockMetadata(x, y, z, metadata ^ 1);
        chunk.setBlockMetadata(x, y, z, metadata);
        chunk.rebuildNonEmptySectionSummary();
    }
}
