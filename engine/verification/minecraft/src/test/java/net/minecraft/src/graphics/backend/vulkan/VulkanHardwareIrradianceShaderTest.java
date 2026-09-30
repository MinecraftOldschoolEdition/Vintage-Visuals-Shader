package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.system.MemoryUtil;

public class VulkanHardwareIrradianceShaderTest {
    @Test
    public void dispatchCoversOddBatchesAndEveryQualityBudget() {
        assertEquals(0, VulkanHardwareIrradianceShader.workgroupCount(0));
        for (int updates = 1; updates <= VulkanHardwareIrradianceVolume.MAX_PROBES; ++updates)
            assertEquals((updates + 1) / 2, VulkanHardwareIrradianceShader.workgroupCount(updates));
    }
    @Test
    public void boundedComputeAndBothProductionHardwareTerrainVariantsCompile() {
        ByteBuffer compute=VulkanShaderCompiler.compileCompute(VulkanHardwareIrradianceShader.computeSource(),"hardware-irradiance-test.comp");
        try { assertTrue(compute.remaining()>0); } finally { MemoryUtil.memFree(compute); }
        for(boolean cube:new boolean[]{false,true}) {
            ByteBuffer fragment=VulkanShaderCompiler.compileFragment(VulkanBuiltinShaderSources.hardwareWorldFragmentShaderSource(cube),"hardware-irradiance-test.frag");
            try { assertTrue(fragment.remaining()>0); } finally { MemoryUtil.memFree(fragment); }
        }
    }
}
