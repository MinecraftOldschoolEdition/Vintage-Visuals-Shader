package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.src.graphics.api.TerrainMaterial;
import net.minecraft.src.graphics.api.TerrainMaterialProfile;
import net.minecraft.src.graphics.api.TerrainMaterialProfiles;
import org.junit.Test;

public class VulkanPbrStateLayoutTest {
    @Test
    public void headerPreservesAllThirtyTwoSlotBitsWithoutMovingEnvironmentData() throws Exception {
        TerrainMaterialProfile[] table = new TerrainMaterialProfile[TerrainMaterialProfiles.PROFILE_COUNT];
        for (int slot = 0; slot < table.length; ++slot)
            table[slot] = TerrainMaterialProfiles.defaults().profileForAlpha(TerrainMaterial.FIRST_ALPHA + slot);
        // The fixture supplies the highest slot as well as ordinary authored
        // slots; signed Java ints must arrive unchanged as GLSL uint bitmasks.
        var constructor = TerrainMaterialProfiles.Snapshot.class.getDeclaredConstructor(long.class,
            TerrainMaterialProfile[].class, int[].class, int[].class);
        constructor.setAccessible(true);
        TerrainMaterialProfiles.Snapshot profiles = constructor.newInstance(4L, table,
            TerrainMaterialProfiles.defaults().packedMapTexels(), new int[]{0x80000001, 1 << 16, 0x80040010});
        ByteBuffer bytes = ByteBuffer.allocate(VulkanPbrState.HEADER_BYTES).order(ByteOrder.nativeOrder());
        VulkanPbrState.writeHeader(bytes, 3, 4, 5, new float[]{.2f,.3f,.4f,1,.1f,.1f,.1f,0}, profiles);
        assertEquals(0x80000001, bytes.getInt(0));
        assertEquals(1 << 16, bytes.getInt(4));
        assertEquals(0x80040010, bytes.getInt(8));
        assertEquals(1, bytes.getInt(12));
        assertEquals(.2f, bytes.getFloat(16), 0);
        assertEquals(3, bytes.getInt(48));
        assertEquals(64, bytes.position());
    }

    @Test
    public void shaderHeaderUsesFourAlignedVectorsAndLeavesTheTableUntouched() {
        ByteBuffer bytes = ByteBuffer.allocate(VulkanPbrState.HEADER_BYTES + 4).order(ByteOrder.nativeOrder());
        bytes.putInt(VulkanPbrState.HEADER_BYTES, 0x13579bdf);
        float[] environment = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0};
        VulkanPbrState.writeHeader(bytes, -24, -72, 40, environment);
        assertEquals(0, bytes.getInt(0));
        assertEquals(0, bytes.getInt(4));
        assertEquals(0, bytes.getInt(8));
        assertEquals(1, bytes.getInt(12));
        assertEquals(0.1f, bytes.getFloat(16), 0);
        assertEquals(0.4f, bytes.getFloat(28), 0);
        assertEquals(0.5f, bytes.getFloat(32), 0);
        assertEquals(0, bytes.getFloat(44), 0);
        assertEquals(-24, bytes.getInt(48));
        assertEquals(-72, bytes.getInt(52));
        assertEquals(40, bytes.getInt(56));
        assertEquals(48, bytes.getInt(60));
        assertEquals(0x13579bdf, bytes.getInt(64));
        bytes.position(0);
        VulkanPbrState.writeDisabledHeader(bytes);
        for (int i = 0; i < 64; i += 4) assertEquals(0, bytes.getInt(i));
        assertEquals(0x13579bdf, bytes.getInt(64));
    }

    @Test
    public void profileMapAndVoxelRegionsMatchTheShaderAddressingWithoutOverlap() {
        assertEquals(64, VulkanPbrState.PROFILE_OFFSET);
        assertEquals(2624, VulkanPbrState.MAP_OFFSET);
        assertEquals(526784, VulkanPbrState.VOLUME_OFFSET);
        assertEquals(969152, VulkanPbrState.WATER_COLUMN_OFFSET);
        assertEquals(VulkanPbrState.WATER_COLUMN_OFFSET + VulkanPbrWaterColumns.BYTES, VulkanPbrState.STORAGE_BYTES);
        TerrainMaterialProfiles.Snapshot profiles = TerrainMaterialProfiles.defaults();
        int[] maps = profiles.packedMapTexels();
        maps[0] = 0x04030201;
        maps[maps.length - 1] = 0x80706050;
        ByteBuffer bytes = ByteBuffer.allocate(VulkanPbrState.STORAGE_BYTES).order(ByteOrder.nativeOrder());
        bytes.putInt(VulkanPbrState.VOLUME_OFFSET, 0x76543210);
        ByteBuffer materialSlice = bytes.duplicate().position(VulkanPbrState.PROFILE_OFFSET)
            .limit(VulkanPbrState.VOLUME_OFFSET).slice();
        VulkanPbrState.writeMaterialData(materialSlice, profiles, maps);
        int leaf = VulkanPbrState.PROFILE_OFFSET + (TerrainMaterial.LEAVES_ALPHA - TerrainMaterial.FIRST_ALPHA) * 80;
        TerrainMaterialProfile leafProfile = profiles.profileForAlpha(TerrainMaterial.LEAVES_ALPHA);
        assertEquals(leafProfile.ior, bytes.getFloat(leaf + 8), 0);
        assertEquals(leafProfile.subsurface, bytes.getFloat(leaf + 12), 0);
        assertEquals(leafProfile.absorptionRed, bytes.getFloat(leaf + 16), 0);
        assertEquals(leafProfile.transmission, bytes.getFloat(leaf + 44), 0);
        assertEquals(leafProfile.textureScale, bytes.getFloat(leaf + 76), 0);
        assertEquals(0x04030201, bytes.getInt(VulkanPbrState.MAP_OFFSET));
        assertEquals(0x80706050, bytes.getInt(VulkanPbrState.VOLUME_OFFSET - 4));
        assertEquals(0x76543210, bytes.getInt(VulkanPbrState.VOLUME_OFFSET));
    }

    @Test
    public void environmentColorsAreLinearAndRejectInvalidInputs() {
        assertEquals(0, VulkanPbrState.srgbToLinear(0), 0);
        assertEquals(1, VulkanPbrState.srgbToLinear(1), 0);
        assertEquals(0.21404114f, VulkanPbrState.srgbToLinear(0.5f), 0.000001f);
        assertEquals(0.0031308f, VulkanPbrState.srgbToLinear(0.04045f), 0.0000001f);
        assertEquals(0, VulkanPbrState.srgbToLinear(Float.NaN), 0);
        assertEquals(0, VulkanPbrState.srgbToLinear(-1), 0);
    }
}
