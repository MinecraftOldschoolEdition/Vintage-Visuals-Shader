package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.system.MemoryUtil;

public class VulkanPbrShaderTest {
    @Test
    public void materialLobesAbsorptionAndEnergyIntegratorCompile() {
        assertCompiles("pbr_transport_helper.frag", VulkanPbrGpuProbe.helperFragmentSource());
    }

    @Test
    public void pbrCompilesInBothWorldFragmentVariants() {
        for (boolean cubeArray : new boolean[]{false, true}) {
            assertCompiles("pbr_world_" + cubeArray + ".frag",
                VulkanBuiltinShaderSources.colorFragmentShaderSource(cubeArray));
        }
    }

    @Test
    public void celestialFragmentsCompileWithThePbrMaterialDecoder() {
        for (boolean cubeArray : new boolean[]{false, true}) {
            assertCompiles("pbr_celestial_" + cubeArray + ".frag",
                VulkanBuiltinShaderSources.celestialFragmentShaderSource(cubeArray));
        }
    }

    private static void assertCompiles(String name, String source) {
        ByteBuffer spirv = VulkanShaderCompiler.compileFragment(source, name);
        try {
            assertTrue(name, spirv.remaining() > 0);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }
}
