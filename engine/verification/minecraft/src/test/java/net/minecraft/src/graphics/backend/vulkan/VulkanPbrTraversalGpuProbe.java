package net.minecraft.src.graphics.backend.vulkan;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Production traversal versus the pre-acceleration scalar GPU oracle, including tied boundaries. */
public final class VulkanPbrTraversalGpuProbe {
    public static void main(String[] args) throws Exception {
        VulkanRayTracingGpuProbe gpu = new VulkanRayTracingGpuProbe();
        try {
            gpu.initialize();
            gpu.replaceProgram(source());
            Random random = new Random(9174638L);
            int comparisons = 0;
            double acceleratedSteps = 0, scalarSteps = 0;
            for (int scene = 0; scene < 6; ++scene) {
                int[] cells = new int[VulkanPbrMaterialVolume.CELL_COUNT];
                if (scene > 0) for (int i = 0; i < 160; ++i) {
                    int x = random.nextInt(48), y = random.nextInt(48), z = random.nextInt(48);
                    cells[x + 48 * (z + 48 * y)] = switch (i % 4) {
                        case 0 -> 192 | 4096;
                        case 1 -> 196 | 256;
                        case 2 -> 198;
                        default -> 197 | 768;
                    };
                }
                if (scene > 2) for (int y = 0; y < 48; ++y) for (int x = 0; x < 48; ++x)
                    cells[x + 48 * (28 + 48 * y)] = scene == 3 ? 196 | 256 : scene == 4 ? 198 : 192 | 4096;
                VulkanPbrMaterialVolume.markEmptyBricks(cells);
                gpu.materialData.write(VulkanPbrState.VOLUME_OFFSET, VulkanPbrState.VOLUME_BYTES,
                    b -> b.order(ByteOrder.nativeOrder()).asIntBuffer().put(cells));
                for (int batch = 0; batch < 128; ++batch) {
                    float[] inputs = new float[64];
                    for (int lane = 0; lane < 8; ++lane) {
                        int o = lane * 8;
                        for (int axis = 0; axis < 3; ++axis) {
                            inputs[o + axis] = batch % 4 == 0 ? random.nextInt(48) - 24 : random.nextFloat() * 48 - 24;
                            inputs[o + 4 + axis] = batch % 4 == 0 ? random.nextInt(3) - 1 : random.nextFloat() * 2 - 1;
                        }
                        if (inputs[o+4] == 0 && inputs[o+5] == 0 && inputs[o+6] == 0) inputs[o+5] = 1;
                        inputs[o+3] = lane % 2;
                        inputs[o+7] = lane % 3 == 0 ? 80 : random.nextFloat() * 40;
                    }
                    gpu.writeRayInputs(inputs);
                    gpu.dispatch(0, null, null, 0, 0, false, false);
                    var result = gpu.results().order(ByteOrder.nativeOrder());
                    for (int lane = 0; lane < 8; ++lane) {
                        for (int c = 0; c < 4; ++c) {
                            float actual = result.getFloat(lane*48+c*4), expected = result.getFloat(lane*48+16+c*4);
                            if (!Float.isFinite(actual) || Math.abs(actual-expected)>0.0002f)
                                throw new AssertionError("scene="+scene+" batch="+batch+" lane="+lane+" channel="+c
                                    +" accelerated="+actual+" scalar="+expected);
                            ++comparisons;
                        }
                        acceleratedSteps += result.getFloat(lane*48+32);
                        scalarSteps += result.getFloat(lane*48+36);
                    }
                }
            }
            if (acceleratedSteps >= scalarSteps * .65) throw new AssertionError("Empty-brick traversal did not reduce work");
            System.out.printf("PBR traversal GPU PASS: %,d channel comparisons; %.0f accelerated / %.0f scalar steps (%.1f%% fewer)%n",
                comparisons, acceleratedSteps, scalarSteps, (1-acceleratedSteps/scalarSteps)*100);
        } finally { gpu.close(); }
    }

    private static String source() throws Exception {
        String world = VulkanPbrWorldShader.SOURCE;
        String body = world.substring(world.indexOf("layout(std430, set = 3, binding = 7)"),world.indexOf("float pbrFarSunVisibility("));
        String reference;
        try (var stream = VulkanPbrTraversalGpuProbe.class.getResourceAsStream("/shaders/pbr_traversal_reference.glsl")) {
            if (stream == null) throw new IllegalStateException("Missing scalar reference");
            reference = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("pbrTrace(","pbrTraceReference(");
        }
        String loop = "for (int iteration = 0; iteration < 144; ++iteration) {";
        body = body.replace(loop,loop+" ++traversalSteps;");
        reference = reference.replace(loop,loop+" ++traversalSteps;");
        return """
            #version 450
            layout(local_size_x=8) in;
            layout(std430,set=0,binding=1) readonly buffer Input { vec4 values[]; } inputData;
            layout(std430,set=0,binding=0) buffer Output { vec4 values[]; } outputData;
            struct ShadowProbe { vec4 cameraPosition; };
            const ShadowProbe shadowData=ShadowProbe(vec4(0));
            bool rtActive() { return false; }
            vec4 rtTransmission(vec3 p,vec3 n,vec3 d,float m,bool t,int q) { return vec4(1); }
            int effectQuality(int effect) { return 2; }
            int traversalSteps=0;
            """+VulkanPbrShader.SOURCE+body.replace("set = 3","set = 0")+reference+"""
            void main() {
                uint lane=gl_GlobalInvocationID.x;
                vec4 a=inputData.values[lane*2u],b=inputData.values[lane*2u+1u];
                vec3 direction=normalize(b.xyz);
                outputData.values[lane*3u]=pbrTrace(a.xyz,vec3(0,1,0),direction,b.w,a.w>.5);
                int accelerated=traversalSteps;traversalSteps=0;
                outputData.values[lane*3u+1u]=pbrTraceReference(a.xyz,vec3(0,1,0),direction,b.w,a.w>.5);
                outputData.values[lane*3u+2u]=vec4(float(accelerated),float(traversalSteps),0,0);
            }
            """;
    }
}
