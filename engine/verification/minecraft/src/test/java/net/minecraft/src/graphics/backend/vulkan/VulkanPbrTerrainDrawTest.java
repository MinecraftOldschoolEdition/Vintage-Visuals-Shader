package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.system.MemoryUtil;

public class VulkanPbrTerrainDrawTest {
    @Test
    public void materialMarkerRequiresTerrainOwnershipEvenWithWorldDepthState() {
        VulkanColorDrawState draw = worldDraw();
        assertFalse(marked(draw, false)); // Ordinary entity with the same alpha byte.
        draw.chunkSkylightSubtracted = -2;
        assertTrue(marked(draw, false)); // CPU terrain with dynamic daylight.
        draw.chunkSkylightSubtracted = 0;
        assertTrue(marked(draw, false)); // CPU terrain with a presented daylight epoch.
        draw.chunkSkylightSubtracted = -1;
        draw.compactTerrainVertexLayout = true;
        assertTrue(marked(draw, false)); // Includes raw FULL_BRIGHT bottom faces.
        draw.compactTerrainVertexLayout = false;
        assertTrue(marked(draw, true)); // GPU chunk terrain.
    }

    @Test
    public void terrainMarkerDoesNotEscapeToGuiHandsParticlesOrShadowPasses() {
        VulkanColorDrawState draw = worldDraw();
        draw.chunkSkylightSubtracted = -2;
        assertFalse(VulkanColorPushConstantBinder.isTerrainMaterialDraw(draw, false, false, false));
        assertFalse(VulkanColorPushConstantBinder.isTerrainMaterialDraw(draw, false, true, true));
        draw.renderPurpose = VulkanRenderPurpose.FIRST_PERSON;
        assertFalse(marked(draw, true));
        draw.renderPurpose = VulkanRenderPurpose.PARTICLE;
        assertFalse(marked(draw, true));
        draw.renderPurpose = VulkanRenderPurpose.MAIN_WORLD;
        draw.cloudMeshDraw = true;
        assertFalse(marked(draw, true));
        draw.cloudMeshDraw = false;
        draw.depthWriteEnabled = false;
        assertFalse(marked(draw, true));
    }

    @Test
    public void allMaterialVertexLayoutsCompileWithTheExtendedWorldUniform() {
        String[] sources = {
            VulkanBuiltinShaderSources.COLOR_VERTEX_SHADER_GLSL,
            VulkanBuiltinShaderSources.COMPACT_TERRAIN_VERTEX_SHADER_GLSL,
            VulkanBuiltinShaderSources.LIT_COLOR_VERTEX_SHADER_GLSL
        };
        for (int i = 0; i < sources.length; i++) {
            ByteBuffer spirv = VulkanShaderCompiler.compileVertex(sources[i], "pbr_terrain_" + i + ".vert");
            try {
                assertTrue(spirv.remaining() > 0);
            } finally {
                MemoryUtil.memFree(spirv);
            }
        }
    }

    private static boolean marked(VulkanColorDrawState draw, boolean gpuChunk) {
        return VulkanColorPushConstantBinder.isTerrainMaterialDraw(draw, gpuChunk, false, true);
    }

    private static VulkanColorDrawState worldDraw() {
        VulkanColorDrawState draw = new VulkanColorDrawState();
        draw.depthEnabled = true;
        draw.depthWriteEnabled = true;
        return draw;
    }
}
