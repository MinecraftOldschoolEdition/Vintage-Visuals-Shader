package net.minecraft.src.graphics.backend.vulkan;

import static org.lwjgl.opengl.GL45C.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.src.graphics.api.TerrainMaterial;
import net.minecraft.src.graphics.api.TerrainMaterialProfile;
import net.minecraft.src.graphics.api.TerrainMaterialProfiles;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;

/**
 * Executes the production linear BSDF in a hidden GPU context. Energy integrals
 * include both hemispheres and run before exposure or display conversion. This
 * explicitly launched probe does not claim a Vulkan or in-game visual check.
 */
public final class VulkanPbrGpuProbe {
    private static final int WIDTH = 384;
    private static final int HEIGHT = 512;
    private static final int SAMPLES = WIDTH * HEIGHT;
    private static final float[] UP = {0, 1, 0};
    private static final float[] DOWN = {0, -1, 0};
    private static final float[] WHITE = {1, 1, 1};
    private static final String VERTEX = """
        #version 450
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    private VulkanPbrGpuProbe() {
    }

    static String helperFragmentSource() {
        return """
            #version 450
            layout(std140, binding = 0) uniform ProbeData {
                vec4 albedo;
                vec4 normal;
                vec4 view;
                vec4 light;
                vec4 surface;
                vec4 absorption;
                vec4 scattering;
                vec4 emission;
                vec4 detail;
                vec4 params;
                vec4 incident;
            } probe;
            layout(location = 0) out vec4 outColor;
            """ + VulkanPbrShader.SOURCE + VulkanReflectionSamplingShader.SOURCE + """
            const float PROBE_PI = 3.141592653589793;
            float radicalInverse(uint value) {
                return float(bitfieldReverse(value)) * 2.3283064365386963e-10;
            }
            vec3 integrateSample(PbrMaterial material) {
                uint sampleIndex = uint(gl_FragCoord.x) + 384u * uint(gl_FragCoord.y);
                uint component = sampleIndex % 3u;
                uint index = sampleIndex / 3u;
                float u = (float(index) + 0.5) / 65536.0;
                float v = fract(radicalInverse(index) + probe.params.w);
                float phi = 2.0 * PROBE_PI * v;
                vec3 view = normalize(probe.view.xyz);
                float roughness = clamp(material.surface.x, 0.045, 1.0);
                float alpha = roughness * roughness;
                float a2 = alpha * alpha;
                vec3 light;
                if (component == 1u) {
                    float cosTheta = sqrt((1.0 - u) / (1.0 + (a2 - 1.0) * u));
                    float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
                    vec3 halfVector = vec3(cos(phi) * sinTheta, cosTheta, sin(phi) * sinTheta);
                    if (dot(view, halfVector) <= 0.0) return vec3(0.0);
                    light = reflect(-view, halfVector);
                } else {
                    float cosTheta = sqrt(1.0 - u);
                    light = vec3(cos(phi) * sqrt(u), component == 2u ? -cosTheta : cosTheta,
                        sin(phi) * sqrt(u));
                }
                float cosine = abs(light.y);
                float density = cosine / (3.0 * PROBE_PI);
                vec3 halfSum = view + light;
                if (dot(halfSum, halfSum) > 0.000000001) {
                    vec3 halfVector = normalize(halfSum);
                    float vh = dot(view, halfVector);
                    if (halfVector.y > 0.0 && vh > 0.0) {
                        float denominator = halfVector.y * halfVector.y * (a2 - 1.0) + 1.0;
                        float distribution = a2 / (PROBE_PI * denominator * denominator);
                        density += distribution * halfVector.y / (12.0 * vh);
                    }
                }
                vec3 bsdf;
                if (probe.params.z == 1.0) bsdf = vec3(light.y > 0.0 ? 1.0 / PROBE_PI : 0.0);
                else if (probe.params.z == 2.0) bsdf = vec3(0.5 / PROBE_PI);
                else if (probe.params.z == 3.0) bsdf = pbrEvaluateBsdf(probe.albedo.rgb,
                    vec3(0.0, 1.0, 0.0), light, view, material, probe.params.y);
                else bsdf = pbrEvaluateBsdf(probe.albedo.rgb, vec3(0.0, 1.0, 0.0), view,
                    light, material, probe.params.y);
                return bsdf * cosine / max(density, 0.000000000001);
            }
            void main() {
                PbrMaterial material = pbrMakeMaterial(probe.surface, probe.absorption,
                    probe.scattering, probe.emission, probe.detail);
                if (probe.params.x == 4.0) {
                    outColor = vec4(pbrBeerLambert(probe.absorption.rgb, probe.params.y), 1.0);
                    return;
                }
                if (probe.params.x == 5.0) {
                    outColor = vec4(integrateSample(material), 1.0);
                    return;
                }
                if (probe.params.x == 7.0) {
                    uint index=uint(gl_FragCoord.x)+384u*uint(gl_FragCoord.y);
                    vec2 point=vec2((float(index)+0.5)/196608.0,radicalInverse(index));
                    vec3 h=rtGgxVisibleNormal(probe.normal.xyz,probe.view.xyz,material.surface.x,point);
                    vec3 direction=reflect(-probe.view.xyz,h);
                    outColor=vec4(h,rtGgxReflectionWeight(dot(direction,probe.normal.xyz),material.surface.x));
                    return;
                }
                if (probe.params.x == 8.0) {
                    outColor=vec4(rtReflectionDetail(material.surface.x),pbrF0(probe.albedo.rgb,material));
                    return;
                }
                vec3 diffuse, specular, transmitted;
                pbrEvaluateLobes(probe.albedo.rgb, probe.normal.xyz, probe.view.xyz,
                    probe.light.xyz, material, probe.params.y, diffuse, specular, transmitted);
                vec3 result = diffuse + specular + transmitted;
                if (probe.params.x == 1.0) result = diffuse;
                else if (probe.params.x == 2.0) result = specular;
                else if (probe.params.x == 3.0) result = transmitted;
                else if (probe.params.x == 6.0) result = pbrIncidentRadiance(probe.albedo.rgb,
                    probe.normal.xyz, probe.view.xyz, probe.light.xyz, material, probe.params.y,
                    probe.incident.rgb);
                outColor = vec4(result, 1.0);
            }
            """;
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Files.createTempDirectory("pbr-transport-gpu-") : Path.of(args[0]);
        Files.createDirectories(output);
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException(SDLError.SDL_GetError());
        long window = 0;
        long context = 0;
        try {
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 4);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 5);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
            window = SDLVideo.SDL_CreateWindow("PBR transport probe", WIDTH, HEIGHT,
                SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
            if (window == 0) throw new IllegalStateException(SDLError.SDL_GetError());
            context = SDLVideo.SDL_GL_CreateContext(window);
            if (context == 0 || !SDLVideo.SDL_GL_MakeCurrent(window, context))
                throw new IllegalStateException(SDLError.SDL_GetError());
            GL.createCapabilities();
            System.out.println("PBR transport GPU=" + glGetString(GL_RENDERER) + " GL=" + glGetString(GL_VERSION));
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            int program = program();
            int query = glGenBuffers();
            glUseProgram(program);
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, query);
            try (Target ignored = new Target()) {
                glDisable(GL_BLEND);
                glDisable(GL_DITHER);
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_CULL_FACE);
                verifyReflectionSampling(query);
                if (!Boolean.getBoolean("probe.reflectionOnly")) {
                verifyBeerLambert(query);
                verifyLobes(query);
                verifyRoughness(query);
                verifyIncidentLightAndDegeneracy(query);
                verifyEnergy(query, output);
                }
            } finally {
                glDeleteBuffers(query);
                glDeleteProgram(program);
                glDeleteVertexArrays(vao);
            }
            int error = glGetError();
            if (error != GL_NO_ERROR) throw new AssertionError("OpenGL error: " + error);
            System.out.println("PBR production linear-BSDF GPU checks PASS; output=" + output);
        } finally {
            GL.setCapabilities(null);
            if (context != 0) SDLVideo.SDL_GL_DestroyContext(context);
            if (window != 0) SDLVideo.SDL_DestroyWindow(window);
            SDLInit.SDL_Quit();
        }
    }

    private static void verifyReflectionSampling(int query) {
        float[] pixels=new float[SAMPLES*4];
        for(float roughness:new float[]{.045f,.25f,.60f,.95f}) {
            for(float cosine:new float[]{1.0f,.10f}) {
                float[] data=material(roughness,0,1.15f,0,0);
                data[36]=7;
                direction(data,8,new float[]{(float)Math.sqrt(1-cosine*cosine),cosine,0});
                upload(query,data);glViewport(0,0,WIDTH,HEIGHT);glDrawArrays(GL_TRIANGLES,0,3);
                glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_FLOAT,pixels);
                int broad=0;
                for(int i=0;i<SAMPLES;i++) {
                    float x=pixels[i*4],y=pixels[i*4+1],z=pixels[i*4+2],weight=pixels[i*4+3];
                    close(1,x*x+y*y+z*z,.00002f,"unit GGX half normal");
                    if(y<0 || x*data[8]+y*cosine<-.00001f || weight<0 || weight>1.00001f)
                        throw new AssertionError("GGX visible normal or Smith weight outside bounds");
                    if(cosine==1) {
                        double u=(i+.5)/SAMPLES,a2=Math.pow(roughness,4);
                        if(u<.99) close((float)Math.sqrt((1-u)/(1+(a2-1)*u)),y,.0001f,"analytic GGX normal-incidence CDF");
                        if(2*y*y-1<Math.cos(Math.PI/4))broad++;
                    }
                }
                if(cosine==1 && roughness>.9f && broad<SAMPLES/2)
                    throw new AssertionError("rough GGX reflections still form a narrow mirror cone");
            }
        }
        TerrainMaterialProfiles.Snapshot profiles=TerrainMaterialProfiles.defaults();
        for(int tag:new int[]{TerrainMaterial.STONE_ALPHA,TerrainMaterial.SOIL_ALPHA,TerrainMaterial.GRASS_ALPHA,
            TerrainMaterial.WOOD_ALPHA,TerrainMaterial.LEAVES_ALPHA,TerrainMaterial.BRICK_ALPHA}) {
            TerrainMaterialProfile profile=profiles.profileForAlpha(tag);
            float[] data=material(profile.roughness,profile.metallic,profile.ior,0,0);data[36]=8;
            float[] actual=sample(query,data);
            close(0,actual[0],0,"matte materials cannot resolve scene mirrors");
            for(int i=1;i<4;i++)if(!(actual[i]>0 && actual[i]<.01f))
                throw new AssertionError("matte dielectric Fresnel energy must remain subtle");
        }
        for(int tag:new int[]{TerrainMaterial.IRON_ALPHA,TerrainMaterial.GOLD_ALPHA,TerrainMaterial.DIAMOND_ALPHA}) {
            TerrainMaterialProfile profile=profiles.profileForAlpha(tag);
            float[] data=material(profile.roughness,profile.metallic,profile.ior,0,0);data[36]=8;
            close(1,sample(query,data)[0],0,"polished material reflection detail remains available");
        }
        System.out.println("GGX VNDF: 8 x "+SAMPLES+" analytic/finite/energy samples; matte defaults and polished metals PASS");
    }

    private static void verifyBeerLambert(int query) {
        float[] data = material(0.5f, 0, 1.5f, 0, 0);
        data[39] = 0;
        data[36] = 4;
        float[] sigma = {0.1f, 0.5f, 1.2f};
        System.arraycopy(sigma, 0, data, 20, 3);
        for (float distance : new float[]{0, 0.01f, 0.5f, 1, 3, 30}) {
            data[37] = distance;
            float[] actual = sample(query, data);
            for (int channel = 0; channel < 3; channel++)
                close((float)Math.exp(-sigma[channel] * distance), actual[channel], 0.000003f,
                    "Beer-Lambert independent exponential");
        }
        data[37] = 0.7f;
        float[] a = sample(query, data);
        data[37] = 1.3f;
        float[] b = sample(query, data);
        data[37] = 2;
        float[] sum = sample(query, data);
        for (int channel = 0; channel < 3; channel++)
            close(a[channel] * b[channel], sum[channel], 0.000003f, "Beer-Lambert path composition");
        System.out.println("Beer-Lambert: analytic attenuation, zero path, and path composition PASS");
    }

    private static void verifyLobes(int query) {
        float[] dielectric = material(0.5f, 0, 1.5f, 0, 0);
        Arrays.fill(dielectric, 0, 3, 0.5f);
        positive(lobe(query, dielectric, 1), "dielectric diffuse");
        positive(lobe(query, dielectric, 2), "dielectric specular");
        zero(lobe(query, dielectric, 3), "opaque transmission");
        float[] metal = dielectric.clone();
        metal[17] = 1;
        metal[19] = 1;
        metal[27] = 1;
        zero(lobe(query, metal, 1), "metal diffuse");
        zero(lobe(query, metal, 3), "metal transmission");
        positive(lobe(query, metal, 2), "metal specular");
        metal[0] = 0.85f; metal[1] = 0.5f; metal[2] = 0.08f;
        float[] colored = lobe(query, metal, 2);
        if (!(colored[0] > colored[1] && colored[1] > colored[2]))
            throw new AssertionError("metal Fresnel lost its wavelength-dependent color");

        float[] matched = material(0.5f, 0, 1, 1, 0);
        direction(matched, 8, new float[]{1, 0.1f, 0});
        direction(matched, 12, new float[]{-1, 0.1f, 0});
        zero(lobe(query, matched, 2), "index-matched dielectric has no grazing reflection");
        for (float value : Arrays.copyOf(lobe(query, matched, 1), 3))
            close((float)(1 / Math.PI), value, 0.000001f, "index-matched interface keeps the diffuse budget");
        float[] metalBefore = lobe(query, metal, 2);
        metal[18] = 1;
        float[] metalAfter = lobe(query, metal, 2);
        for (int channel = 0; channel < 3; channel++)
            close(metalBefore[channel], metalAfter[channel], 0.000001f, "conductor Fresnel remains independent of dielectric IOR");

        float[] leaf = material(0.65f, 0, 1.5f, 0.8f, 0.65f);
        leaf[20] = 0.3f; leaf[21] = 0.1f; leaf[22] = 0.7f;
        leaf[24] = 0.2f; leaf[25] = 0.8f; leaf[26] = 0.15f;
        positive(lobe(query, leaf, 1), "subsurface front diffuse");
        zero(lobe(query, leaf, 3), "front transmission hemisphere");
        direction(leaf, 12, DOWN);
        zero(lobe(query, leaf, 1), "back diffuse hemisphere");
        zero(lobe(query, leaf, 2), "back specular hemisphere");
        leaf[37] = 0.05f;
        float[] thin = lobe(query, leaf, 3);
        leaf[37] = 2;
        float[] thick = lobe(query, leaf, 3);
        positive(thin, "thin-leaf back transmission");
        for (int channel = 0; channel < 3; channel++)
            if (!(thin[channel] > thick[channel])) throw new AssertionError("thickness did not attenuate leaf channel " + channel);
        float[] opaque = leaf.clone();
        opaque[27] = 0;
        zero(lobe(query, opaque, 3), "zero transmission suppresses backlighting");
        direction(leaf, 12, UP);
        leaf[27] = 0;
        float[] allDiffuse = lobe(query, leaf, 1);
        leaf[27] = 0.5f;
        float[] halfDiffuse = lobe(query, leaf, 1);
        leaf[27] = 1;
        zero(lobe(query, leaf, 1), "all transmission removes front diffuse budget");
        for (int channel = 0; channel < 3; channel++)
            close(allDiffuse[channel] * 0.5f, halfDiffuse[channel], 0.00001f, "diffuse/transmission partition");
        System.out.println("Lobes: dielectric/metal, colored Fresnel, front/back leaf, thin/thick absorption, partition PASS");
    }

    private static void verifyRoughness(int query) {
        float[] smooth = material(0.20f, 1, 1.5f, 0, 0);
        float[] rough = material(0.85f, 1, 1.5f, 0, 0);
        float smoothPeak = lobe(query, smooth, 2)[0];
        float roughPeak = lobe(query, rough, 2)[0];
        direction(smooth, 8, new float[]{4, 2, 0});
        direction(rough, 8, new float[]{4, 2, 0});
        float smoothFlank = lobe(query, smooth, 2)[0];
        float roughFlank = lobe(query, rough, 2)[0];
        if (!(smoothPeak > roughPeak && roughFlank > smoothFlank))
            throw new AssertionError("roughness must broaden and lower the specular lobe");
        System.out.println("Roughness: sharp/smooth and broad/rough specular lobes PASS");
    }

    private static void verifyIncidentLightAndDegeneracy(int query) {
        float[] data = material(0.5f, 0, 1.5f, 0.5f, 0.4f);
        data[36] = 6;
        direction(data, 40, new float[3]);
        zero(sample(query, data), "all incoming radiance zero");
        float[] local = {1, 0.2f, 0.04f};
        direction(data, 40, local);
        float[] one = sample(query, data);
        direction(data, 40, new float[]{2, 0.4f, 0.08f});
        float[] twice = sample(query, data);
        direction(data, 40, new float[]{0.1f, 0.4f, 0.8f});
        float[] indirect = sample(query, data);
        direction(data, 40, new float[]{1.1f, 0.6f, 0.84f});
        float[] combined = sample(query, data);
        for (int channel = 0; channel < 3; channel++) {
            close(one[channel] * 2, twice[channel], 0.00001f, "linear incident-light response");
            close(one[channel] + indirect[channel], combined[channel], 0.00001f, "local plus indirect source additivity");
        }
        if (!(one[0] > one[1] && one[1] > one[2])) throw new AssertionError("colored local light lost its hue");
        int checked = 0;
        data[36] = 0;
        for (float roughness : new float[]{0, 0.045f, 0.2f, 1}) {
            data[16] = roughness;
            for (float[] normal : new float[][]{UP, new float[3], {0, 0.0000001f, 0}})
                for (float[] view : new float[][]{UP, new float[3], DOWN, {1, 0, 0}, {1, 0.000001f, 0}})
                    for (float[] light : new float[][]{UP, new float[3], DOWN, {1, 0, 0}, {-1, 0.000001f, 0}}) {
                        direction(data, 4, normal); direction(data, 8, view); direction(data, 12, light);
                        sample(query, data);
                        checked++;
                    }
        }
        System.out.println("Incident radiance zero/linearity/additivity and finite degenerate cases=" + checked + " PASS");
    }

    private static void verifyEnergy(int query, Path output) throws Exception {
        StringBuilder csv = new StringBuilder("case,roughness,metallic,view_cosine,transmission,subsurface,red,green,blue\n");
        float[] fixture = material(0.2f, 0, 1.5f, 0, 0);
        for (int oracle = 1; oracle <= 2; oracle++) {
            fixture[38] = oracle;
            double[] integral = integrate(query, fixture);
            for (double channel : integral) close(1, (float)channel, 0.002f, "analytic Lambert quadrature calibration");
        }
        double largest = 0;
        int cases = 0;
        for (float roughness : new float[]{0.045f, 0.08f, 0.24f, 0.6f, 1})
            for (float metallic : new float[]{0, 1})
                for (float cosine : new float[]{1, 0.5f, 0.1f, 0.02f}) {
                    float[] data = material(roughness, metallic, 1.5f, 0, 0);
                    direction(data, 8, new float[]{(float)Math.sqrt(1 - cosine * cosine), cosine, 0});
                    double[] integral = integrate(query, data);
                    largest = Math.max(largest, conserving(integral, "opaque"));
                    append(csv, "opaque", data, cosine, integral);
                    // Integrate outgoing directions for fixed incident light
                    // as well as the white-furnace incoming hemisphere. These
                    // need not coincide for approximate thickness models.
                    data[38] = 3;
                    double[] outgoing = integrate(query, data);
                    largest = Math.max(largest, conserving(outgoing, "opaque outgoing"));
                    append(csv, "opaque_outgoing", data, cosine, outgoing);
                    cases++;
                }
        for (float transmission : new float[]{0.5f, 1})
            for (float subsurface : new float[]{0, 1})
                for (float cosine : new float[]{1, 0.1f}) {
                    float[] data = material(0.5f, 0, 1.5f, subsurface, transmission);
                    direction(data, 8, new float[]{(float)Math.sqrt(1 - cosine * cosine), cosine, 0});
                    double[] integral = integrate(query, data);
                    largest = Math.max(largest, conserving(integral, "translucent"));
                    append(csv, "translucent", data, cosine, integral);
                    data[38] = 3;
                    double[] outgoing = integrate(query, data);
                    largest = Math.max(largest, conserving(outgoing, "translucent outgoing"));
                    append(csv, "translucent_outgoing", data, cosine, outgoing);
                    cases++;
                }
        float[] black = material(0.5f, 0, 1, 0, 0);
        Arrays.fill(black, 0, 3, 0);
        for (double channel : integrate(query, black)) close(0, (float)channel, 0.000001f, "black nonreflecting absorption");
        float[] absorbing = material(0.5f, 0, 1.5f, 0.5f, 0.8f);
        Arrays.fill(absorbing, 20, 23, 0.6f);
        absorbing[37] = 0.05f;
        double[] thin = integrate(query, absorbing);
        absorbing[37] = 3;
        double[] thick = integrate(query, absorbing);
        for (int channel = 0; channel < 3; channel++)
            if (!(thin[channel] > thick[channel] + 0.01)) throw new AssertionError("integrated absorption must increase with path length");
        append(csv, "absorbing_thick", absorbing, 1, thick);
        // Re-run a glossy grazing case with a rotated low-discrepancy sequence.
        float[] convergence = material(0.08f, 1, 1.5f, 0, 0);
        direction(convergence, 8, new float[]{(float)Math.sqrt(0.99), 0.1f, 0});
        double[] first = integrate(query, convergence);
        convergence[39] = 0.371f;
        double[] second = integrate(query, convergence);
        for (int channel = 0; channel < 3; channel++)
            close((float)first[channel], (float)second[channel], 0.006f, "importance-sampling convergence");
        Files.writeString(output.resolve("hemispherical-energy.csv"), csv);
        System.out.println("Energy: " + cases + " reflection+transmission cases x 2 integral directions x " + SAMPLES
            + " MIS samples, max integrated channel=" + largest + "; absorption/convergence PASS");
    }

    private static double conserving(double[] integral, String label) {
        double largest = 0;
        for (double value : integral) {
            if (!Double.isFinite(value) || value < -0.000001 || value > 1.01)
                throw new AssertionError(label + " violates conservation: " + Arrays.toString(integral));
            largest = Math.max(largest, value);
        }
        return largest;
    }

    private static void append(StringBuilder csv, String name, float[] data, float cosine, double[] integral) {
        csv.append(name).append(',').append(data[16]).append(',').append(data[17]).append(',').append(cosine)
            .append(',').append(data[27]).append(',').append(data[19]);
        for (double value : integral) csv.append(',').append(value);
        csv.append('\n');
    }

    private static double[] integrate(int query, float[] values) {
        float[] data = values.clone();
        data[36] = 5;
        upload(query, data);
        glViewport(0, 0, WIDTH, HEIGHT);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] pixels = new float[SAMPLES * 4];
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_FLOAT, pixels);
        double[] sum = new double[3];
        for (int i = 0; i < pixels.length; i += 4)
            for (int channel = 0; channel < 3; channel++) {
                float value = pixels[i + channel];
                if (!Float.isFinite(value) || value < -0.000001f)
                    throw new AssertionError("nonfinite or negative BSDF energy sample=" + value);
                sum[channel] += value;
            }
        for (int channel = 0; channel < 3; channel++) sum[channel] /= SAMPLES;
        return sum;
    }

    private static float[] lobe(int query, float[] data, int selected) {
        float[] values = data.clone();
        values[36] = selected;
        return sample(query, values);
    }

    private static float[] material(float roughness, float metallic, float ior, float subsurface, float transmission) {
        float[] values = new float[44];
        direction(values, 0, WHITE);
        direction(values, 4, UP);
        direction(values, 8, UP);
        direction(values, 12, UP);
        values[16] = roughness; values[17] = metallic; values[18] = ior; values[19] = subsurface;
        values[23] = 0.1f;
        direction(values, 24, WHITE);
        values[27] = transmission;
        values[32] = 1; values[33] = 1; values[35] = 1;
        values[37] = 0.1f;
        return values;
    }

    private static void direction(float[] values, int offset, float[] direction) {
        System.arraycopy(direction, 0, values, offset, 3);
    }

    private static float[] sample(int query, float[] values) {
        upload(query, values);
        glViewport(0, 0, 1, 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] pixel = new float[4];
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, pixel);
        for (float value : pixel) if (!Float.isFinite(value))
            throw new AssertionError("nonfinite BSDF output: " + Arrays.toString(pixel));
        return pixel;
    }

    private static void upload(int query, float[] values) {
        glBindBuffer(GL_UNIFORM_BUFFER, query);
        glBufferData(GL_UNIFORM_BUFFER, values, GL_STREAM_DRAW);
    }

    private static int program() {
        int program = glCreateProgram();
        for (int stage : new int[]{GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(stage);
            glShaderSource(shader, stage == GL_VERTEX_SHADER ? VERTEX : helperFragmentSource());
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new AssertionError(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new AssertionError(glGetProgramInfoLog(program));
        return program;
    }

    private static void positive(float[] values, String label) {
        for (int channel = 0; channel < 3; channel++)
            if (!(values[channel] > 0)) throw new AssertionError(label + ": " + Arrays.toString(values));
    }

    private static void zero(float[] values, String label) {
        for (int channel = 0; channel < 3; channel++) close(0, values[channel], 0.000001f, label);
    }

    private static void close(float expected, float actual, float tolerance, String label) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
    }

    private static final class Target implements AutoCloseable {
        private final int texture;
        private final int framebuffer;

        private Target() {
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, WIDTH, HEIGHT, 0, GL_RGBA, GL_FLOAT, (float[])null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new AssertionError("incomplete PBR transport probe framebuffer");
        }

        @Override
        public void close() {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(texture);
        }
    }
}
