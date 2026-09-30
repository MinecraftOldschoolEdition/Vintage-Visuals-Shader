package net.minecraft.src.graphics.backend.vulkan;

import java.util.Arrays;
import net.minecraft.src.block.Block;
import net.minecraft.src.graphics.api.TerrainMaterial;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.Level;

/** Nearby optical material cells, rebuilt only after block changes or an eight-block camera step. */
final class VulkanPbrMaterialVolumeReference {
    static final int SIZE = 48;
    static final int CELL_COUNT = SIZE * SIZE * SIZE;
    static final int EMPTY_BRICK_BIT = 1 << 16;
    static final int OPAQUE_BIT = 1 << 12;
    static final int UNLOADED_CELL = 15 << 8 | OPAQUE_BIT;
    private static final int ANCHOR_STEP = 8;
    private static final int MAX_CHUNKS_PER_AXIS = SIZE / 16 + 1;

    private final int[] cells = new int[CELL_COUNT];
    private final ChunkAccess[] chunks = new ChunkAccess[MAX_CHUNKS_PER_AXIS * MAX_CHUNKS_PER_AXIS];
    private final long[] chunkIdentities = new long[this.chunks.length];
    private final int[] chunkRevisions = new int[this.chunks.length];
    private Level world;
    private int originX;
    private int originY;
    private int originZ;
    private int minBuildHeight;
    private int maxBuildHeight;
    private long revision;

    boolean update(Level world, float cameraX, float cameraY, float cameraZ) {
        if (world == null || !Float.isFinite(cameraX) || !Float.isFinite(cameraY) || !Float.isFinite(cameraZ)) {
            clear();
            return false;
        }
        int nextX = originFor(cameraX);
        int nextY = originFor(cameraY);
        int nextZ = originFor(cameraZ);
        boolean changed = this.world != world || this.originX != nextX || this.originY != nextY || this.originZ != nextZ
            || this.minBuildHeight != world.getMinBuildHeight() || this.maxBuildHeight != world.getMaxBuildHeight();
        this.world = world;
        this.originX = nextX;
        this.originY = nextY;
        this.originZ = nextZ;
        this.minBuildHeight = world.getMinBuildHeight();
        this.maxBuildHeight = world.getMaxBuildHeight();

        int minChunkX = nextX >> 4;
        int minChunkZ = nextZ >> 4;
        int chunkCountX = ((nextX + SIZE - 1) >> 4) - minChunkX + 1;
        int chunkCountZ = ((nextZ + SIZE - 1) >> 4) - minChunkZ + 1;
        for (int z = 0; z < chunkCountZ; ++z) for (int x = 0; x < chunkCountX; ++x) {
            int slot = x + z * MAX_CHUNKS_PER_AXIS;
            // Read loaded snapshots only; no chunk admission, generation, or light work.
            ChunkAccess chunk = world.getChunkForRenderSnapshot(minChunkX + x, minChunkZ + z, false);
            long identity = chunk == null ? 0L : chunk.getRenderSnapshotIdentity();
            int blockRevision = chunk == null ? 0 : chunk.getBlockStateRevision();
            changed |= this.chunkIdentities[slot] != identity || this.chunkRevisions[slot] != blockRevision;
            this.chunks[slot] = chunk;
            this.chunkIdentities[slot] = identity;
            this.chunkRevisions[slot] = blockRevision;
        }
        if (!changed) return true;

        for (int y = 0; y < SIZE; ++y) {
            int worldY = nextY + y;
            for (int z = 0; z < SIZE; ++z) {
                int worldZ = nextZ + z;
                for (int x = 0; x < SIZE; ++x) {
                    int worldX = nextX + x;
                    ChunkAccess chunk = this.chunks[((worldX >> 4) - minChunkX)
                        + MAX_CHUNKS_PER_AXIS * ((worldZ >> 4) - minChunkZ)];
                    int value;
                    if (chunk == null || worldY < this.minBuildHeight) {
                        value = UNLOADED_CELL;
                    } else if (worldY >= this.maxBuildHeight) {
                        value = 0;
                    } else {
                        int blockId = chunk.getBlockID(worldX & 15, worldY, worldZ & 15);
                        int metadata = blockId == 0 ? 0 : chunk.getBlockMetadata(worldX & 15, worldY, worldZ & 15);
                        value = encode(blockId, metadata);
                    }
                    this.cells[x + SIZE * (z + SIZE * y)] = value;
                }
            }
        }
        markEmptyBricks(this.cells);
        ++this.revision;
        return true;
    }

    /** Preserve material words; only completely empty aligned bricks get skip metadata. */
    static void markEmptyBricks(int[] cells) {
        for (int y = 0; y < SIZE; y += 4) for (int z = 0; z < SIZE; z += 4) for (int x = 0; x < SIZE; x += 4) {
            int combined = 0;
            for (int dy = 0; dy < 4; ++dy) for (int dz = 0; dz < 4; ++dz) {
                int first = x + SIZE * (z + dz + SIZE * (y + dy));
                for (int dx = 0; dx < 4; ++dx) combined |= cells[first + dx] & ~EMPTY_BRICK_BIT;
            }
            for (int dy = 0; dy < 4; ++dy) for (int dz = 0; dz < 4; ++dz) {
                int first = x + SIZE * (z + dz + SIZE * (y + dy));
                for (int dx = 0; dx < 4; ++dx)
                    cells[first + dx] = combined == 0 ? EMPTY_BRICK_BIT : cells[first + dx] & ~EMPTY_BRICK_BIT;
            }
        }
    }

    void clear() {
        if (this.world == null) return;
        this.world = null;
        Arrays.fill(this.cells, 0);
        Arrays.fill(this.chunks, null);
        Arrays.fill(this.chunkIdentities, 0L);
        Arrays.fill(this.chunkRevisions, 0);
        ++this.revision;
    }

    int originX() { return this.originX; }
    int originY() { return this.originY; }
    int originZ() { return this.originZ; }
    long revision() { return this.revision; }
    int[] cells() { return this.cells; }

    static int originFor(float cameraPosition) {
        return Math.floorDiv((int)Math.floor(cameraPosition), ANCHOR_STEP) * ANCHOR_STEP - SIZE / 2;
    }

    /** Low byte is the stable material alpha tag, then four opacity bits and an opaque-cube bit. */
    static int encode(int blockId, int metadata) {
        if (blockId == 0) return 0;
        if (blockId < 0 || blockId >= Block.blocksList.length || Block.blocksList[blockId] == null)
            return UNLOADED_CELL;
        int alpha = TerrainMaterial.alphaTagForBlock(blockId, metadata);
        int opacity = Math.clamp(Block.lightOpacity[blockId], 0, 15);
        // Fast leaves can report an opaque render cube, while their optical
        // opacity stays one. They must remain transmissive in either graphics mode.
        boolean opaque = opacity == 15 && Block.opaqueCubeLookup[blockId];
        return alpha | opacity << 8 | (opaque ? OPAQUE_BIT : 0);
    }
}
