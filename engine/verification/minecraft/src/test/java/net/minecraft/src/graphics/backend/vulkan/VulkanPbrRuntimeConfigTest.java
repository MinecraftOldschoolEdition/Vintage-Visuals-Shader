package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.src.graphics.api.ShaderLightingMode;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

public class VulkanPbrRuntimeConfigTest {
    private String previousMode;

    @Before
    public void selectRasterizedEffects() {
        previousMode = System.getProperty(ShaderLightingMode.REQUESTED_MODE_PROPERTY);
        System.setProperty(ShaderLightingMode.REQUESTED_MODE_PROPERTY, "rasterized");
    }

    @After
    public void restoreMode() {
        if (previousMode == null) System.clearProperty(ShaderLightingMode.REQUESTED_MODE_PROPERTY);
        else System.setProperty(ShaderLightingMode.REQUESTED_MODE_PROPERTY, previousMode);
    }

    private static final String SUN_PROPERTY = "minecraft.vulkan.dynamicShadows.enabled";
    private static final String EMISSIVE_PROPERTY = "minecraft.vulkan.emissiveShadowCasting.enabled";
    private static final String COLORED_PROPERTY = "minecraft.vulkan.coloredGlobalIllumination.enabled";
    private static final String PBR_PROPERTY = "minecraft.vulkan.pbrMaterials.enabled";

    @Test
    public void pbrDefaultsOffAndRemainsIndependentOfGiAndShadows() {
        try (SavedProperties ignored = new SavedProperties()) {
            System.setProperty(SUN_PROPERTY, "true");
            System.setProperty(EMISSIVE_PROPERTY, "true");
            System.setProperty(COLORED_PROPERTY, "true");
            System.clearProperty(PBR_PROPERTY);
            assertFalse(VulkanRuntimeConfig.pbrMaterialsEnabled());

            System.setProperty(SUN_PROPERTY, "false");
            System.setProperty(EMISSIVE_PROPERTY, "false");
            System.setProperty(COLORED_PROPERTY, "false");
            System.setProperty(PBR_PROPERTY, "true");
            assertTrue(VulkanRuntimeConfig.pbrMaterialsEnabled());
            assertFalse(VulkanRuntimeConfig.anyShadowCastingEnabled());
            assertFalse(VulkanRuntimeConfig.coloredGlobalIlluminationEnabled());

            System.setProperty(PBR_PROPERTY, "false");
            assertFalse(VulkanRuntimeConfig.pbrMaterialsEnabled());
            assertFalse(VulkanRuntimeConfig.anyShadowCastingEnabled());
            assertFalse(VulkanRuntimeConfig.coloredGlobalIlluminationEnabled());
        }
    }

    @Test
    public void pbrAloneAdmitsEnvironmentPreparationAndStopsOnRuntimeToggle() {
        try (SavedProperties ignored = new SavedProperties()) {
            System.setProperty(SUN_PROPERTY, "false");
            System.setProperty(EMISSIVE_PROPERTY, "false");
            System.setProperty(COLORED_PROPERTY, "false");
            System.setProperty(PBR_PROPERTY, "true");
            VulkanRuntime runtime = new VulkanRuntime();
            VulkanRuntimeUpscalerServices upscalers = runtime.upscalerServices;
            VulkanFrameRecordSubmitContext context = new VulkanFrameRecordSubmitContext(
                runtime, upscalers.upscalerState, upscalers.upscalerPolicyOperations,
                upscalers.fsrAuxiliaryOperations);

            VulkanFrameRenderPlan.Inputs planInputs = new VulkanFrameRenderPlan.Inputs();
            planInputs.worldCommandCount = 1;
            planInputs.vulkanDynamicShadowsEnabled = context.capture().vulkanDynamicShadowsEnabled;
            assertTrue(VulkanFrameRenderPlan.resolve(planInputs).dynamicShadowsEnabled());
            assertFalse(VulkanRuntimeConfig.anyShadowCastingEnabled());
            assertFalse(VulkanRuntimeConfig.coloredGlobalIlluminationEnabled());

            planInputs.worldCommandCount = 0;
            assertFalse(VulkanFrameRenderPlan.resolve(planInputs).dynamicShadowsEnabled());

            System.setProperty(PBR_PROPERTY, "false");
            assertFalse(VulkanRuntimeConfig.pbrMaterialsEnabled());
            planInputs.worldCommandCount = 1;
            planInputs.vulkanDynamicShadowsEnabled = context.capture().vulkanDynamicShadowsEnabled;
            assertEquals(VulkanMinecraftRenderContext.anyWorldLightingEffectsEnabled(),
                VulkanFrameRenderPlan.resolve(planInputs).dynamicShadowsEnabled());
        }
    }

    private static final class SavedProperties implements AutoCloseable {
        private final String[] names = {SUN_PROPERTY, EMISSIVE_PROPERTY, COLORED_PROPERTY, PBR_PROPERTY};
        private final String[] values = new String[names.length];

        private SavedProperties() {
            for (int i = 0; i < names.length; i++) values[i] = System.getProperty(names[i]);
        }

        @Override
        public void close() {
            for (int i = 0; i < names.length; i++) {
                if (values[i] == null) System.clearProperty(names[i]);
                else System.setProperty(names[i], values[i]);
            }
        }
    }
}
