package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.system.MemoryUtil;

public class VulkanPbrWaterCausticShaderTest {
    @Test
    public void productionSurfaceHelperCompilesForNativeNumericalOracle() {
        ByteBuffer code=VulkanShaderCompiler.compileCompute(VulkanPbrWaterCausticGpuProbe.source(),"caustic-helper.comp");
        try { assertTrue(code.remaining()>20); }
        finally { MemoryUtil.memFree(code); }
    }

    @Test
    public void causticsModifyOnlyVisibleSunlightAndBothWorldModesDeclareTheAnimatedArray() {
        String source=VulkanPbrWorldShader.SOURCE;
        int begin=source.indexOf("vec3 sun = shadowData.sunDirection.xyz;",source.indexOf("vec3 pbrShadeSurface("));
        int end=source.indexOf("float localSunlight =",begin);
        String sunlight=source.substring(begin,end);
        assertTrue(sunlight.contains("rtSunTransmission("));
        assertTrue(sunlight.contains("pbrTrace("));
        assertTrue(sunlight.contains("farVisibility*cloudVisibility) * path.rgb"));
        assertTrue(sunlight.contains("if (pbrWaterCausticReceiver(tag) && any(greaterThan(incident,vec3(0.0))))"));
        assertTrue(sunlight.contains("incident *= pbrWaterCausticGain("));
        assertFalse(source.substring(end).contains("pbrWaterCausticGain("));
        for(boolean hardware:new boolean[]{false,true}) {
            String world=VulkanBuiltinShaderSources.colorFragmentShaderSource(false,hardware);
            assertTrue(world.contains("uniform sampler2DArray pbrCausticSampler"));
            assertTrue(world.contains("vec4 causticAnimation"));
            ByteBuffer code=VulkanShaderCompiler.compileFragment(world,"caustic-world-"+hardware+".frag");
            try {assertTrue(code.remaining()>20);} finally {MemoryUtil.memFree(code);}
        }
    }
}
