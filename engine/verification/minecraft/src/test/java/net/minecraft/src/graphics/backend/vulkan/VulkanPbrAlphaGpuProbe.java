package net.minecraft.src.graphics.backend.vulkan;

import static org.lwjgl.opengl.GL45C.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.src.graphics.api.TerrainMaterial;
import net.minecraft.src.graphics.api.TerrainMaterialProfiles;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;

/** Explicit GPU regression for material tags in the complete production fragment shader. */
public final class VulkanPbrAlphaGpuProbe {
    private static final String VERTEX = """
        #version 450
        uniform float vertexAlpha;
        uniform float skyLight;
        uniform vec2 vanillaLight;
        layout(location = 0) out vec4 fragColor;
        layout(location = 1) out vec2 fragUv;
        layout(location = 2) out float fragFogCoord;
        layout(location = 3) out vec3 fragVisualLight;
        layout(location = 4) out vec4 fragShadowClip;
        layout(location = 5) out vec3 fragEmissivePosition;
        layout(location = 6) out float fragSkyLight;
        layout(location = 7) out vec4 fragCloudShadowClip;
        layout(location = 8) out vec3 fragLocalPosition;
        layout(location = 9) out vec2 fragVanillaLight;
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            fragColor = vec4(vec3(0.5), vertexAlpha);
            fragUv = p;
            fragFogCoord = 0.0;
            fragVisualLight = vec3(1.0, 0.0, -2.0);
            fragShadowClip = vec4(0.0);
            fragEmissivePosition = vec3(p.x, 0.0, p.y);
            fragSkyLight = skyLight;
            fragCloudShadowClip = vec4(0.0);
            fragLocalPosition = fragEmissivePosition;
            fragVanillaLight = vanillaLight;
        }
        """;

    public static void main(String[] args) {
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException(SDLError.SDL_GetError());
        long window = 0;
        long context = 0;
        try {
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 4);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 5);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
            window = SDLVideo.SDL_CreateWindow("PBR alpha regression", 16, 16,
                SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
            if (window == 0) throw new IllegalStateException(SDLError.SDL_GetError());
            context = SDLVideo.SDL_GL_CreateContext(window);
            if (context == 0 || !SDLVideo.SDL_GL_MakeCurrent(window, context))
                throw new IllegalStateException(SDLError.SDL_GetError());
            GL.createCapabilities();
            verify();
        } finally {
            if (context != 0) SDLVideo.SDL_GL_DestroyContext(context);
            if (window != 0) SDLVideo.SDL_DestroyWindow(window);
            SDLInit.SDL_Quit();
        }
    }

    private static void verify() {
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int program = program();
        glUseProgram(program);
        int target = texture();
        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new AssertionError("incomplete PBR alpha framebuffer");
        int sample = texture();
        for (int unit : new int[]{0, 1, 2, 3, 5}) glBindTextureUnit(unit, sample);
        int shadow = glGenBuffers();
        int push = glGenBuffers();
        int volume = glGenBuffers();
        int pbr = glGenBuffers();
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, shadow);
        glBindBufferBase(GL_UNIFORM_BUFFER, 1, push);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, volume);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VulkanPbrState.STORAGE_BINDING, pbr);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, volume);
        glBufferData(GL_SHADER_STORAGE_BUFFER, new int[4], GL_STATIC_DRAW);
        uploadMaterials(pbr, true);
        glUniform1f(glGetUniformLocation(program, "skyLight"), 1);
        glUniform2f(glGetUniformLocation(program, "vanillaLight"), 0, 1);
        glViewport(0, 0, 1, 1);
        glDisable(GL_BLEND);
        glDisable(GL_DITHER);
        glDisable(GL_DEPTH_TEST);
        int checked = 0;
        try {
            // GUI, world entity, first-person item, CPU terrain, GPU terrain.
            for (int mode = 0; mode < 5; mode++) for (boolean enabled : new boolean[]{false, true}) {
                upload(shadow, shadowUniforms(enabled, true));
                for (float textureAlpha : new float[]{1, 0.5f}) for (int alpha : alphaCases()) {
                    glTextureSubImage2D(sample, 0, 0, 0, 1, 1, GL_RGBA, GL_FLOAT,
                        new float[]{1, 1, 1, textureAlpha});
                    glUniform1f(glGetUniformLocation(program, "vertexAlpha"), alpha / 255.0f);
                    float[] constants = new float[56];
                    constants[16] = mode == 4 ? 2 : 1;
                    constants[17] = mode == 0 ? 0 : mode == 1 ? 2 : mode == 2 ? 6 : 2.25f;
                    constants[32] = constants[33] = 1;
                    upload(push, constants);
                    float expected = mode == 4 || mode == 3 && TerrainMaterial.isMaterialAlphaTag(alpha)
                        ? textureAlpha : alpha / 255.0f * textureAlpha;
                    check(expected, draw(), "mode=" + mode + " enabled=" + enabled + " alpha=" + alpha);
                    // Alpha testing must observe restored terrain alpha, and
                    // must still discard translucent GUI/entity/texture pixels.
                    constants[23] = 18.9f; // GREATER 0.9, packed alpha-test state.
                    upload(push, constants);
                    check(expected > 0.9f ? expected : 0, draw(), "alpha-test mode=" + mode + " alpha=" + alpha);
                    checked += 2;
                }
            }
            verifyMaterialLighting(program, sample, shadow, push, pbr);
            verifyMaterialMaps(program, pbr);
            verifyMaterialMapPhase(program, shadow);
            System.out.println("PBR production-fragment alpha GPU cases=" + checked + " GPU=" + glGetString(GL_RENDERER));
        } finally {
            glDeleteBuffers(shadow);
            glDeleteBuffers(push);
            glDeleteBuffers(volume);
            glDeleteBuffers(pbr);
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(target);
            glDeleteTextures(sample);
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
        }
    }

    private static int[] alphaCases() {
        int[] values = new int[TerrainMaterial.PROFILE_COUNT + 3];
        values[0] = 128;
        values[1] = TerrainMaterial.FIRST_ALPHA - 1;
        for (int slot = 0; slot < TerrainMaterial.PROFILE_COUNT; ++slot) values[slot + 2] = TerrainMaterial.FIRST_ALPHA + slot;
        values[values.length - 1] = 255;
        return values;
    }

    private static float[] shadowUniforms(boolean enabled, boolean daylight) {
        float[] uniforms = new float[VulkanEmissiveLightUniforms.SHADOW_UNIFORM_FLOATS];
        uniforms[87] = daylight ? 1 : 0;
        uniforms[90] = daylight ? 1 : 0;
        uniforms[93] = 1;
        uniforms[96] = .5f;
        uniforms[97] = 8;
        uniforms[98] = .5f;
        uniforms[908] = enabled ? 1 : 0;
        uniforms[910] = 10922; // HIGH in every two-bit effect field.
        return uniforms;
    }

    private static void uploadMaterials(int buffer, boolean daylight) {
        ByteBuffer bytes = MemoryUtil.memCalloc(VulkanPbrState.STORAGE_BYTES).order(ByteOrder.nativeOrder());
        try {
            float[] environment = daylight ? new float[] { .18f, .3f, .45f, 1, .025f, .025f, .025f, 0 } : new float[8];
            VulkanPbrState.writeHeader(bytes, -24, -24, -24, environment);
            TerrainMaterialProfiles.Snapshot profiles = TerrainMaterialProfiles.defaults();
            VulkanPbrState.writeMaterialData(bytes.slice(VulkanPbrState.PROFILE_OFFSET,
                VulkanPbrState.PROFILE_BYTES + VulkanPbrState.MAP_BYTES), profiles, profiles.packedMapTexels());
            // Cleared optical cells model unobstructed air around the test surface.
            bytes.clear();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    private static void verifyMaterialLighting(int program, int sample, int shadow, int push, int pbr) {
        glTextureSubImage2D(sample, 0, 0, 0, 1, 1, GL_RGBA, GL_FLOAT, new float[] { .8f, .65f, .4f, 1 });
        float[] constants = new float[56];
        constants[16] = 1;
        constants[17] = 2.25f;
        constants[32] = constants[33] = 1;
        upload(push, constants);
        glUniform1f(glGetUniformLocation(program, "vertexAlpha"), TerrainMaterial.SAND_ALPHA / 255.0f);
        upload(shadow, shadowUniforms(false, true));
        float[] disabled = drawPixel();
        upload(shadow, shadowUniforms(true, true));
        float[] sandDay = drawPixel();
        glUniform1f(glGetUniformLocation(program, "vertexAlpha"), TerrainMaterial.IRON_ALPHA / 255.0f);
        float[] ironDay = drawPixel();
        glUniform1f(glGetUniformLocation(program, "vertexAlpha"), TerrainMaterial.SAND_ALPHA / 255.0f);
        upload(shadow, shadowUniforms(true, false));
        uploadMaterials(pbr, false);
        float[] sandNight = drawPixel();
        if (rgbDistance(disabled, sandDay) < .0001f) throw new AssertionError("PBR enable made no surface lighting change");
        if (rgbDistance(sandDay, ironDay) < .0001f) throw new AssertionError("Sand and iron use the same complete-fragment response");
        if (luminance(sandDay) <= luminance(sandNight) + .0001f)
            throw new AssertionError("Sunlit sand did not exceed its unlit night response");
        for (float[] pixel : new float[][] { disabled, sandDay, ironDay, sandNight }) check(1, pixel[3], "material RGB check alpha");
        System.out.println("PBR production-fragment lighting day=" + luminance(sandDay)
            + " night=" + luminance(sandNight) + " iron=" + luminance(ironDay));
    }

    private static float rgbDistance(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    private static float luminance(float[] pixel) { return .2126f * pixel[0] + .7152f * pixel[1] + .0722f * pixel[2]; }

    private static void verifyMaterialMaps(int worldProgram, int buffer) {
        int[] texels = new int[TerrainMaterialProfiles.MAP_TEXEL_COUNT];
        for (int slot = 0; slot < 32; ++slot) for (int map = 0; map < 3; ++map) for (int mip = 0; mip < 6; ++mip) {
            int size = 32 >> mip;
            int offset = TerrainMaterialProfiles.mapTexelOffset(slot, map, mip);
            for (int y = 0; y < size; ++y) for (int x = 0; x < size; ++x) {
                int red = (slot * 5 + map * 31 + mip * 7) & 255;
                int green = (x * 7 + y * 13 + 17 * mip) & 255;
                int blue = (map * 47 + mip * 23) & 255;
                texels[offset + x + size * y] = red | green << 8 | blue << 16 | 255 << 24;
            }
        }
        glNamedBufferSubData(buffer, VulkanPbrState.MAP_OFFSET, texels);
        int samplerProgram = program("""
            uniform int probeSlot;
            uniform int probeMap;
            uniform vec2 probeUv;
            uniform float probeLod;
            void main() { outColor = pbrMap(probeSlot,probeMap,probeUv,probeLod); }
            """);
        int checked = 0;
        try {
            glUseProgram(samplerProgram);
            for (int slot : new int[] { 0, 4, 16, 17, 31 }) for (int map = 0; map < 3; ++map)
                for (float[] uv : new float[][] { { 0, 0 }, { 1, 1 }, { -.0625f, 1.03125f }, { .19f, .83f } })
                    for (float lod : new float[] { 0, .75f, 2.25f, 5 }) {
                        glUniform1i(glGetUniformLocation(samplerProgram,"probeSlot"),slot);
                        glUniform1i(glGetUniformLocation(samplerProgram,"probeMap"),map);
                        glUniform2f(glGetUniformLocation(samplerProgram,"probeUv"),uv[0],uv[1]);
                        glUniform1f(glGetUniformLocation(samplerProgram,"probeLod"),lod);
                        float[] actual = drawPixel();
                        int low = (int)Math.floor(lod);
                        float[] a = expectedMapLevel(texels,slot,map,uv,low);
                        float[] b = expectedMapLevel(texels,slot,map,uv,Math.min(5,low+1));
                        for (int channel = 0; channel < 4; ++channel) {
                            float expected = a[channel] + (b[channel]-a[channel])*(lod-low);
                            check(expected,actual[channel],"map slot="+slot+" map="+map+" lod="+lod+" channel="+channel);
                        }
                        ++checked;
                    }
            System.out.println("PBR production material-map GPU cases="+checked);
        } finally {
            glUseProgram(worldProgram);
            glDeleteProgram(samplerProgram);
        }
    }

    private static float[] expectedMapLevel(int[] texels, int slot, int map, float[] uv, int mip) {
        int size = 32 >> mip;
        float px = (uv[0]-(float)Math.floor(uv[0]))*size-.5f;
        float py = (uv[1]-(float)Math.floor(uv[1]))*size-.5f;
        int x = (int)Math.floor(px), y = (int)Math.floor(py);
        float fx = px-x, fy = py-y;
        float[] result = new float[4];
        int offset = TerrainMaterialProfiles.mapTexelOffset(slot,map,mip);
        for (int cy = 0; cy < 2; ++cy) for (int cx = 0; cx < 2; ++cx) {
            int pixel = texels[offset+Math.floorMod(x+cx,size)+size*Math.floorMod(y+cy,size)];
            float weight = (cx==0 ? 1-fx : fx)*(cy==0 ? 1-fy : fy);
            for (int channel = 0; channel < 4; ++channel) {
                float value = ((pixel >>> (channel*8)) & 255)/255.0f;
                if (map==2 && channel<3) value=VulkanPbrState.srgbToLinear(value);
                result[channel]+=value*weight;
            }
        }
        return result;
    }

    private static void verifyMaterialMapPhase(int worldProgram, int shadow) {
        int phaseProgram = program("""
            uniform vec3 probePosition;
            uniform vec3 probeTangent;
            uniform vec3 probeBitangent;
            uniform float probeScale;
            void main() {
                outColor = vec4(fract(pbrMaterialUv(probePosition,probeTangent,probeBitangent,probeScale)),0.0,1.0);
            }
            """);
        int checked = 0;
        float[][] tangents = { { 0,0,1 }, { 0,0,-1 }, { 1,0,0 } };
        float[][] bitangents = { { 1,0,0 }, { 0,1,0 }, { 0,-1,0 } };
        try {
            glUseProgram(phaseProgram);
            for (int base : new int[] { 4032,4096,-4096,-4160,32000000,-32000000 })
                for (float scale : new float[] { .1f,.01f,1.3f,32,.125f }) for (int plane=0;plane<3;++plane) {
                    double[] world = { base+64.375, -base+64.25, base+192.875 };
                    float[] tangent=tangents[plane], bitangent=bitangents[plane];
                    for (int rebase : new int[] { 0,64 }) {
                        float[] uniforms=shadowUniforms(true,true);
                        uniforms[100]=base+rebase;
                        uniforms[101]=-base+rebase;
                        uniforms[102]=base+128+rebase;
                        upload(shadow,uniforms);
                        glUniform3f(glGetUniformLocation(phaseProgram,"probePosition"),
                            (float)(world[0]-uniforms[100]),(float)(world[1]-uniforms[101]),(float)(world[2]-uniforms[102]));
                        glUniform3f(glGetUniformLocation(phaseProgram,"probeTangent"),tangent[0],tangent[1],tangent[2]);
                        glUniform3f(glGetUniformLocation(phaseProgram,"probeBitangent"),bitangent[0],bitangent[1],bitangent[2]);
                        glUniform1f(glGetUniformLocation(phaseProgram,"probeScale"),scale);
                        float[] pixel=drawPixel();
                        double expectedU=(world[0]*tangent[0]+world[1]*tangent[1]+world[2]*tangent[2])*scale;
                        double expectedV=(world[0]*bitangent[0]+world[1]*bitangent[1]+world[2]*bitangent[2])*scale;
                        checkPhase(expectedU,pixel[0],"U base="+base+" scale="+scale+" rebase="+rebase);
                        checkPhase(expectedV,pixel[1],"V base="+base+" scale="+scale+" rebase="+rebase);
                        ++checked;
                    }
                }
            System.out.println("PBR production material-map rebase GPU cases="+checked);
        } finally {
            glUseProgram(worldProgram);
            glDeleteProgram(phaseProgram);
        }
    }

    private static void checkPhase(double expected, float actual, String label) {
        expected-=Math.floor(expected);
        double error=Math.abs(expected-actual);
        error=Math.min(error,Math.abs(1-error));
        if (!Float.isFinite(actual) || error>0.00002)
            throw new AssertionError(label+": expected phase="+expected+" actual="+actual);
    }

    private static int texture() {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, 1, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }

    private static void upload(int buffer, float[] values) {
        glBindBuffer(GL_UNIFORM_BUFFER, buffer);
        glBufferData(GL_UNIFORM_BUFFER, values, GL_STREAM_DRAW);
    }

    private static float draw() {
        return drawPixel()[3];
    }

    private static float[] drawPixel() {
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] pixel = new float[4];
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, pixel);
        for (float channel : pixel) if (!Float.isFinite(channel)) throw new AssertionError("Nonfinite production-fragment output");
        return pixel;
    }

    private static void check(float expected, float actual, String label) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > 0.00001f)
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
    }

    private static int program() {
        return program(null);
    }

    private static int program(String probeMain) {
        String fragment = VulkanBuiltinShaderSources.colorFragmentShaderSource(false)
            .replace("layout(push_constant)", "layout(std140, binding = 1)")
            .replace("set = 2, binding = 0", "binding = 2")
            .replaceAll("set\\s*=\\s*\\d+\\s*,\\s*", "");
        if (probeMain != null) fragment = fragment.replace("void main() {", "void unusedProductionMain() {") + probeMain;
        int program = glCreateProgram();
        for (int stage : new int[]{GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(stage);
            glShaderSource(shader, stage == GL_VERTEX_SHADER ? VERTEX : fragment);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new AssertionError(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new AssertionError(glGetProgramInfoLog(program));
        return program;
    }
}
