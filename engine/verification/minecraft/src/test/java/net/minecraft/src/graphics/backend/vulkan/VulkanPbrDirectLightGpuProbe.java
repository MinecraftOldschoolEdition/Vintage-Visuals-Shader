package net.minecraft.src.graphics.backend.vulkan;

import static org.lwjgl.opengl.GL45C.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import net.minecraft.src.graphics.api.TerrainMaterialProfiles;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;

/**
 * Differential execution of the actual PBR point-light loop, including its BSDF
 * and voxel transmission, against the frozen pre-reuse loop. Optional paired
 * compute timestamps isolate this loop; they are not Vulkan world-frame timings.
 */
public final class VulkanPbrDirectLightGpuProbe {
    private static final int RECEIVERS = 8192;
    private static final int RESULT_BYTES = RECEIVERS * 48;
    private static final int SHADOW_BYTES = 80 + 64 * 64 + VulkanPointLightGrid.STORAGE_BYTES;
    private final int[] buffers = new int[4];
    private final int[] programs = new int[2];
    private int comparisons;
    private long contributingReceivers;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--compile-only")) {
            for (boolean reference : new boolean[]{true, false}) {
                ByteBuffer spirv = VulkanShaderCompiler.compileCompute(source(reference, true), "pbr_direct_reuse.comp");
                try { System.out.println("PBR direct " + (reference ? "reference" : "production") + " compile PASS: " + spirv.remaining()); }
                finally { MemoryUtil.memFree(spirv); }
            }
            return;
        }
        boolean performance = Arrays.asList(args).contains("--benchmark");
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException(SDLError.SDL_GetError());
        long window = 0, context = 0;
        VulkanPbrDirectLightGpuProbe probe = new VulkanPbrDirectLightGpuProbe();
        try {
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 4);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 5);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
            window = SDLVideo.SDL_CreateWindow("PBR direct light parity", 64, 64, SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
            if (window == 0) throw new IllegalStateException(SDLError.SDL_GetError());
            context = SDLVideo.SDL_GL_CreateContext(window);
            if (context == 0 || !SDLVideo.SDL_GL_MakeCurrent(window, context)) throw new IllegalStateException(SDLError.SDL_GetError());
            GL.createCapabilities();
            System.out.println("PBR direct-light GPU=" + glGetString(GL_RENDERER));
            probe.initialize();
            probe.verify();
            if (performance) probe.benchmark();
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("OpenGL error");
        } finally {
            if (context != 0) {
                for (int program : probe.programs) glDeleteProgram(program);
                for (int buffer : probe.buffers) glDeleteBuffers(buffer);
                GL.setCapabilities(null);
                SDLVideo.SDL_GL_DestroyContext(context);
            }
            if (window != 0) SDLVideo.SDL_DestroyWindow(window);
            SDLInit.SDL_Quit();
        }
    }

    private void initialize() throws Exception {
        for (int i = 0; i < buffers.length; ++i) buffers[i] = glGenBuffers();
        bind(0, 0); glBufferData(GL_SHADER_STORAGE_BUFFER, RESULT_BYTES, GL_DYNAMIC_READ);
        bind(1, 1);
        ByteBuffer receivers = MemoryUtil.memAlloc(RECEIVERS * 32).order(ByteOrder.nativeOrder());
        try {
            Random random = new Random(783472);
            for (int i = 0; i < RECEIVERS; ++i) {
                // Include cell/sphere boundaries, outside-grid fallback and both hemispheres.
                float x = i % 17 == 0 ? 64 : i % 13 == 0 ? -64.001f : random.nextFloat() * 40 - 20;
                float y = i % 7 == 0 ? 0 : random.nextFloat() * 40 - 20;
                float z = i % 11 == 0 ? 16 : random.nextFloat() * 40 - 20;
                if (i < 16) { x=0; y=0; z=0; }
                receivers.putFloat(x).putFloat(y).putFloat(z).putFloat((i % 9) / 8f);
                receivers.putFloat(i < 16 ? (i % 2 == 0 ? 1 : -1) : 0)
                    .putFloat(i < 16 ? 0 : (i % 2 == 0 ? 1 : -1)).putFloat(0)
                    .putFloat(new int[]{192,196,198,205,208,209,210,211,194,193,201,202,203,199,200,204}[i % 16]);
            }
            receivers.flip(); glBufferData(GL_SHADER_STORAGE_BUFFER, receivers, GL_STATIC_DRAW);
        } finally { MemoryUtil.memFree(receivers); }
        bind(2, 2); glBufferData(GL_SHADER_STORAGE_BUFFER, SHADOW_BYTES, GL_DYNAMIC_DRAW);
        bind(3, 7); glBufferData(GL_SHADER_STORAGE_BUFFER, VulkanPbrState.STORAGE_BYTES, GL_DYNAMIC_DRAW);
        programs[0] = program(source(true, true)); programs[1] = program(source(false, true));
    }

    private void verify() {
        float maximumError = 0;
        int cases = 0;
        for (int scene = 0; scene < 6; ++scene) {
            materials(scene);
            for (int quality = 0; quality < 4; ++quality) for (boolean grid : new boolean[]{false, true}) {
                for (int lights : new int[]{0, 1, 8, 31, 32, 33, 63, 64}) {
                    settings(scene, quality, lights, grid);
                    ByteBuffer reference = MemoryUtil.memAlloc(RESULT_BYTES).order(ByteOrder.nativeOrder());
                    ByteBuffer actual = MemoryUtil.memAlloc(RESULT_BYTES).order(ByteOrder.nativeOrder());
                    try {
                        dispatch(programs[0]); read(reference);
                        dispatch(programs[1]); read(actual);
                        for (int i = 0; i < RECEIVERS; ++i) {
                            int offset = i * 48;
                            for (int c = 0; c < 3; ++c) {
                                float expected = reference.getFloat(offset+c*4), value = actual.getFloat(offset+c*4);
                                maximumError = Math.max(maximumError, Math.abs(value-expected));
                                if (!Float.isFinite(value) || Math.abs(value-expected) > Math.max(1e-6f, Math.abs(expected)*2e-6f))
                                    throw new AssertionError("scene="+scene+" quality="+quality+" grid="+grid+" lights="+lights
                                        +" receiver="+i+" channel="+c+" reference="+expected+" production="+value);
                                ++comparisons;
                            }
                            // Exact admission mask and cached helper contracts, including float raw bits.
                            for (int c = 4; c < 12; ++c) {
                                if (actual.getInt(offset+c*4) != reference.getInt(offset+c*4))
                                    throw new AssertionError("selected mask/evaluation mismatch: scene="+scene+" receiver="+i+" word="+c);
                                ++comparisons;
                            }
                            if (actual.getInt(offset+16) != 0 || actual.getInt(offset+20) != 0) ++contributingReceivers;
                        }
                    } finally { MemoryUtil.memFree(reference); MemoryUtil.memFree(actual); }
                    ++cases;
                }
            }
        }
        if (contributingReceivers < 1000) throw new AssertionError("Fixture did not exercise local lighting");
        System.out.printf("PBR direct-light differential PASS: %,d cases, %,d receiver channel/mask/evaluation checks, %,d contributing receivers, max error %.9g%n",
            cases, comparisons, contributingReceivers, maximumError);
    }

    private void benchmark() throws Exception {
        for (int i = 0; i < 2; ++i) { glDeleteProgram(programs[i]); programs[i] = program(source(i == 0, false)); }
        int query = glGenQueries();
        try {
            for (int scene : new int[]{0, 1, 2}) for (int quality : new int[]{0, 3}) {
                materials(scene); settings(scene, quality, 64, true);
                for (int warmup = 0; warmup < 12; ++warmup) { dispatch(programs[warmup % 2]); glFinish(); }
                long[][] times = new long[2][31];
                for (int sample = 0; sample < times[0].length; ++sample) for (int slot = 0; slot < 2; ++slot) {
                    int variant = (sample + slot) % 2;
                    glUseProgram(programs[variant]);
                    glBeginQuery(GL_TIME_ELAPSED, query);
                    glDispatchCompute(RECEIVERS / 64, 1, 1);
                    glEndQuery(GL_TIME_ELAPSED);
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
                    times[variant][sample] = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                }
                Arrays.sort(times[0]); Arrays.sort(times[1]);
                System.out.printf("PBR direct paired compute: scene=%s quality=%d receivers=%d reference=%.3f us candidate=%.3f us change=%+.2f%% p95=%.3f/%.3f us%n",
                    new String[]{"air", "opaque", "foliage"}[scene], quality, RECEIVERS,
                    times[0][15]/1000.0, times[1][15]/1000.0, 100.0*(times[1][15]/(double)times[0][15]-1),
                    times[0][29]/1000.0, times[1][29]/1000.0);
            }
        } finally { glDeleteQueries(query); }
    }

    private void materials(int scene) {
        ByteBuffer bytes = MemoryUtil.memCalloc(VulkanPbrState.STORAGE_BYTES).order(ByteOrder.nativeOrder());
        try {
            VulkanPbrState.writeHeader(bytes, -24, -24, -24, new float[8]);
            bytes.position(VulkanPbrState.PROFILE_OFFSET);
            var profiles = TerrainMaterialProfiles.defaults();
            VulkanPbrState.writeMaterialData(bytes.slice().order(ByteOrder.nativeOrder()), profiles, profiles.packedMapTexels());
            int[] cells = new int[VulkanPbrMaterialVolume.CELL_COUNT];
            Random random = new Random(8877);
            if (scene != 0) for (int i = 0; i < 1800; ++i) cells[random.nextInt(cells.length)] = switch (scene) {
                case 1 -> 192 | 4096; case 2 -> 196 | 256; case 3 -> 198; case 4 -> 197 | 768;
                default -> i % 3 == 0 ? 192 | 4096 : i % 3 == 1 ? 196 | 256 : 198;
            };
            if (scene > 1) for (int x = 0; x < 48; ++x) for (int z = 0; z < 48; ++z)
                cells[x + 48*(z+48*25)] = scene == 2 ? 196 | 256 : scene == 3 ? 198 : 197 | 768;
            VulkanPbrMaterialVolume.markEmptyBricks(cells);
            bytes.position(VulkanPbrState.VOLUME_OFFSET); bytes.asIntBuffer().put(cells);
            bytes.position(0); bind(3, 7); glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, bytes);
        } finally { MemoryUtil.memFree(bytes); }
    }

    private void settings(int scene, int quality, int count, boolean gridEnabled) {
        VulkanEmissiveLight[] lights = new VulkanEmissiveLight[count];
        Random random = new Random(17953 + scene);
        for (int i = 0; i < count; ++i) {
            float x = random.nextFloat()*32-16, y = random.nextFloat()*32-16, z = random.nextFloat()*32-16;
            if (i < 6) { x = new float[]{0, .049999f, .05f, 17.99999f, 18, 18.00001f}[i]; y=0; z=0; }
            float radius = i < 6 ? 18 : new float[]{18, 24, 8, 0, 17.99999f}[i%5];
            int kind = i%3;
            float strength = i%7 == 0 ? 0 : i%11 == 0 ? -1 : kind == 1 ? .04f : .4f;
            lights[i] = new VulkanEmissiveLight(kind, x,y,z,radius,radius,
                i%13 == 0 ? 0 : random.nextFloat(), i%13 == 0 ? 0 : random.nextFloat(),
                i%13 == 0 ? 0 : random.nextFloat(), strength, 0);
        }
        ByteBuffer bytes = MemoryUtil.memCalloc(SHADOW_BYTES).order(ByteOrder.nativeOrder());
        try {
            bytes.putFloat(8, quality).putFloat(64+4, count).putFloat(64+12, scene%2);
            for (int i = 0; i < count; ++i) {
                var l = lights[i];
                bytes.position(80+i*16); bytes.putFloat(l.x).putFloat(l.y).putFloat(l.z).putFloat(l.radius);
                bytes.position(80+64*16+i*16); bytes.putFloat(l.red).putFloat(l.green).putFloat(l.blue).putFloat(l.strength);
                bytes.putFloat(80+64*32+i*16+8, l.kind);
            }
            bytes.position(80+64*48);
            VulkanEmissiveLightUniforms.writeLinearColors(bytes.slice().order(ByteOrder.nativeOrder()).asFloatBuffer(), lights, count);
            VulkanPointLightGrid grid = new VulkanPointLightGrid(); grid.update(lights, count, 0,0,0);
            bytes.position(80+64*64); grid.write(bytes, gridEnabled, 0,0,0);
            bytes.position(0); bind(2, 2); glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, bytes);
        } finally { MemoryUtil.memFree(bytes); }
    }

    private void bind(int buffer, int binding) { glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[buffer]); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,binding,buffers[buffer]); }
    private void dispatch(int program) { glUseProgram(program); glDispatchCompute(RECEIVERS/64,1,1); glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT); }
    private void read(ByteBuffer result) { bind(0,0); glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,result); }

    private static int program(String source) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new AssertionError(glGetShaderInfoLog(shader));
        int program = glCreateProgram(); glAttachShader(program, shader); glLinkProgram(program); glDeleteShader(shader);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new AssertionError(glGetProgramInfoLog(program));
        return program;
    }

    static String source(boolean reference, boolean diagnostics) throws Exception {
        String saved;
        try (var stream = VulkanPbrDirectLightGpuProbe.class.getResourceAsStream("/shaders/pbr_direct_light_reference.glsl")) {
            if (stream == null) throw new IllegalStateException("Missing frozen PBR direct-light oracle");
            saved = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] savedParts = saved.split("// PBR_POINT_LOOP\\n", 2);
        String world = VulkanPbrWorldShader.SOURCE;
        String material = world.substring(world.indexOf("layout(std430, set = 3, binding = 7)"), world.indexOf("float pbrFarSunVisibility("));
        String loop = reference ? savedParts[1] : world.substring(world.indexOf("float localSunlight ="), world.indexOf("if (m.emission.w>0.0"));
        // Compare the last eligible source's unmodified evaluation against the
        // old caller's recomputed values, as well as the final sum and selection.
        if (diagnostics) loop = loop.replace("vec3 incident =", "selected[lightIndex/32] |= 1u<<uint(lightIndex%32); "
            + "lastDirectionDistance=vec4(direction,distance); lastStrengthWindow=vec2(strength,window); vec3 incident =");
        String bsdf=VulkanPbrShader.SOURCE;
        if(reference) try(var stream=VulkanPbrDirectLightGpuProbe.class.getResourceAsStream("/shaders/pbr_bsdf_v1_1_reference.glsl")) {
            if(stream==null)throw new IllegalStateException("Missing frozen PBR BSDF oracle");
            bsdf=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        return """
            #version 450
            layout(local_size_x=64) in;
            layout(std430,binding=0) buffer Results { vec4 values[]; } outputData;
            layout(std430,binding=1) readonly buffer Inputs { vec4 values[]; } inputData;
            layout(std430,binding=2) readonly buffer Shadow {
                vec4 materialParams; vec4 sunDirection; vec4 cameraPosition; vec4 environmentMeta; vec4 emissiveMeta;
                vec4 emissivePositionRadius[64]; vec4 emissiveColorStrength[64]; vec4 emissiveProperties[64];
                vec4 emissiveLinearColorLuminance[64]; ivec4 emissiveGridOriginSize; uvec4 emissiveGridMasks[256];
            } shadowData;
            bool rtActive() { return false; }
            int effectQuality(int effect) { return int(shadowData.materialParams.z); }
            vec4 rtTransmission(vec3 p,vec3 n,vec3 d,float m,bool t,int q) { return vec4(1); }
            vec3 rtPointTransmissionSingle(vec3 p,vec3 n,vec3 s,bool t,int q) { return vec3(1); }
            """ + bsdf + VulkanEmissiveLightingShader.SOURCE
            + (reference ? savedParts[0] : VulkanRayTracingLightSamplingShader.SOURCE)
            + material.replace("set = 3, ", "") + """
            void main() {
                uint lane=gl_GlobalInvocationID.x;
                vec4 a=inputData.values[lane*2u], b=inputData.values[lane*2u+1u];
                vec3 position=a.xyz,n=b.xyz,geometricNormal=n,view=n;
                float skyLight=a.w;
                int quality=effectQuality(3);
                PbrMaterial m=pbrData.profiles[int(b.w)-192];
                vec3 albedo=vec3(.12,.37,.68),radiance=vec3(0);
                bool thin=m.scattering.w>0.0;
                float thickness=m.absorption.w;
                uvec2 selected=uvec2(0);
                vec4 lastDirectionDistance=vec4(0);vec2 lastStrengthWindow=vec2(0);
            """ + loop + """
                outputData.values[lane*3u]=vec4(radiance,1);
            """ + (diagnostics ? """
                outputData.values[lane*3u+1u]=vec4(uintBitsToFloat(selected),lastStrengthWindow);
                outputData.values[lane*3u+2u]=lastDirectionDistance;
            """ : "") + "}\n";
    }
}
