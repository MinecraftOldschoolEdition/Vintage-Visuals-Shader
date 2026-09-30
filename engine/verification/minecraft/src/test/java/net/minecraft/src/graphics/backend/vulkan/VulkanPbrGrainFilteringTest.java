package net.minecraft.src.graphics.backend.vulkan;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;

public class VulkanPbrGrainFilteringTest {
    @Test
    public void productionGrainBlockMatchesUnfilteredFormulaAndSkipsZeroContributionNoiseOnCpu() throws Exception {
        Path compiler = Path.of("/usr/bin/g++");
        Assume.assumeTrue("CPU C++ compiler is optional outside Linux CI", Files.isExecutable(compiler));
        String source = VulkanPbrWorldShader.SOURCE;
        int begin = source.indexOf("float resolved = 1.0-smoothstep");
        int end = source.indexOf("n = normalize(tangent*normalMap.x", begin);
        int sandBegin = source.indexOf("if (sandGrains)", end);
        int sandEnd = source.indexOf("float thickness =", sandBegin);
        assertTrue(begin >= 0 && end > begin && sandBegin > end && sandEnd > sandBegin);
        String block = (source.substring(begin,end) + source.substring(sandBegin,sandEnd))
            .replaceAll("(?<![\\w.])(\\d+\\.\\d+(?:e[+-]?\\d+)?)(?![\\w.])", "$1f");
        Path directory = Files.createTempDirectory("pbr-grain-cpu-");
        try {
            Path cpp = directory.resolve("grain.cpp");
            Path binary = directory.resolve("grain");
            Files.writeString(cpp, ADAPTER + block + CASES);
            run(directory, compiler.toString(), "-std=c++17", "-O2", "-ffp-contract=off", "-fno-fast-math",
                cpp.toString(), "-o", binary.toString());
            assertTrue(run(directory,binary.toString()).contains("648 grain parity/no-op cases passed"));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static String run(Path directory, String... command) throws Exception {
        Path log = directory.resolve("output.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("CPU grain check timed out");
        }
        String result = Files.readString(log);
        assertEquals(result,0,process.exitValue());
        return result;
    }

    private static final String ADAPTER = """
        #include <algorithm>
        #include <cmath>
        #include <cstdlib>
        #include <iostream>
        using std::clamp;
        struct vec2 { float x,y;vec2(float a=0,float b=0):x(a),y(b){};
            void operator+=(vec2 v){x+=v.x;y+=v.y;} };
        vec2 operator*(vec2 a,float b){return vec2(a.x*b,a.y*b);}
        struct vec3 {float x,y,z;vec3(float a=0,float b=0,float c=0):x(a),y(b),z(c){}};
        vec3 operator+(vec3 a,vec3 b){return vec3(a.x+b.x,a.y+b.y,a.z+b.z);}
        struct Material {vec3 detail,surface;};
        struct Normal {vec2 xy;};
        struct Result {vec2 perturbation;float roughness;int calls;};
        int noiseCalls=0;
        float pbrGrain(vec3 p){++noiseCalls;return p.z==0?0.97f:0.23f;}
        float smoothstep(float a,float b,float x){float t=clamp((x-a)/(b-a),0.0f,1.0f);return t*t*(3-2*t);}
        float mix(float a,float b,float t){return a*(1-t)+b*t;}
        Result evaluate(float footprint,float strength,float scale,int quality,bool sand){
            Material m;m.detail=vec3(scale,1,strength);m.surface=vec3(0.96f,0,0);
            float tag=sand?208:192;Normal normalMap;normalMap.xy=vec2(0.13f,-0.27f);
            vec3 position;noiseCalls=0;
        """;

    private static final String CASES = """
            return {normalMap.xy,m.surface.x,noiseCalls};
        }
        void near(float actual,float expected){
            if(!std::isfinite(actual)||std::abs(actual-expected)>0.0000002f){
                std::cerr<<"grain response changed "<<actual<<" vs "<<expected<<"\\n";std::exit(1);
            }
        }
        int main(){int checked=0;
            for(float footprint:{0.0f,0.49f,0.5f,0.75f,1.0f,1.499f,1.5f,4.0f,32.0f})
            for(float strength:{0.0f,0.03f,0.6f})for(float scale:{0.0f,0.5f,1.0f})
            for(int quality:{0,1,2,3})for(bool sand:{false,true}){
                Result result=evaluate(footprint,strength,scale,quality,sand);
                float resolved=1-smoothstep(0.5f,1.5f,footprint);
                // Original always-evaluated equations are the numerical oracle.
                float amount=strength*scale*resolved*(quality==0?0.0f:0.24f);
                near(result.perturbation.x,0.13f+(0.97f-0.5f)*amount);
                near(result.perturbation.y,-0.27f+(0.23f-0.5f)*amount);
                near(result.roughness,sand?mix(0.96f,0.22f,smoothstep(0.93f,0.99f,0.97f)*resolved*strength):0.96f);
                int expected=resolved<=0||strength<=0?0:(scale>0&&quality>0?2:(sand?1:0));
                if(result.calls!=expected){std::cerr<<"evaluated noise with no contribution\\n";return 1;}
                ++checked;
            }
            std::cout<<checked<<" grain parity/no-op cases passed\\n";
        }
        """;
}
