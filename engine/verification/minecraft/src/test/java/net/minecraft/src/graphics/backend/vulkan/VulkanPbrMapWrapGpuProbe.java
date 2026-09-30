package net.minecraft.src.graphics.backend.vulkan;

import static org.lwjgl.opengl.GL45C.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;

/** Exact authored-map differential, including signed extremes; optional isolated GPU sampling cost. */
public final class VulkanPbrMapWrapGpuProbe {
    private static final int SAMPLES = 32768;
    private static final int BYTES = SAMPLES * 48;

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--compile-only")) {
            for (boolean reference : new boolean[]{true,false}) {
                ByteBuffer spirv=VulkanShaderCompiler.compileCompute(source(reference,true),"map_wrap.comp");
                try { System.out.println("PBR map wrap " + (reference ? "reference" : "candidate") + " compile PASS: " + spirv.remaining()); }
                finally { MemoryUtil.memFree(spirv); }
            }
            return;
        }
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException(SDLError.SDL_GetError());
        long window=0,context=0;
        int[] buffers=new int[3],programs=new int[2];
        ByteBuffer input=MemoryUtil.memAlloc(SAMPLES*32).order(ByteOrder.nativeOrder());
        ByteBuffer reference=MemoryUtil.memAlloc(BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer actual=MemoryUtil.memAlloc(BYTES).order(ByteOrder.nativeOrder());
        try {
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION,4);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION,5);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
            window=SDLVideo.SDL_CreateWindow("PBR map wrap parity",64,64,SDLVideo.SDL_WINDOW_OPENGL|SDLVideo.SDL_WINDOW_HIDDEN);
            if(window==0)throw new IllegalStateException(SDLError.SDL_GetError());
            context=SDLVideo.SDL_GL_CreateContext(window);
            if(context==0 || !SDLVideo.SDL_GL_MakeCurrent(window,context))throw new IllegalStateException(SDLError.SDL_GetError());
            GL.createCapabilities();
            System.out.println("PBR map wrap GPU="+glGetString(GL_RENDERER));
            for(int i=0;i<3;++i)buffers[i]=glGenBuffers();
            bind(buffers[0],0);glBufferData(GL_SHADER_STORAGE_BUFFER,BYTES,GL_DYNAMIC_READ);
            bind(buffers[1],1);glBufferData(GL_SHADER_STORAGE_BUFFER,SAMPLES*32,GL_DYNAMIC_DRAW);
            ByteBuffer maps=MemoryUtil.memCalloc(VulkanPbrState.VOLUME_OFFSET).order(ByteOrder.nativeOrder());
            try {
                // Every pixel differs, so wrapping bugs cannot hide behind flat mip colors.
                Random random=new Random(99317);
                for(int offset=VulkanPbrState.MAP_OFFSET;offset<maps.capacity();offset+=4)maps.putInt(offset,random.nextInt());
                bind(buffers[2],7);glBufferData(GL_SHADER_STORAGE_BUFFER,maps,GL_STATIC_DRAW);
            } finally {MemoryUtil.memFree(maps);}
            programs[0]=program(source(true,true)); programs[1]=program(source(false,true));
            int words=0;
            for(int cohort=0;cohort<8;++cohort) {
                inputs(input,cohort,true);bind(buffers[1],1);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,input);
                dispatch(programs[0]);bind(buffers[0],0);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,reference);
                dispatch(programs[1]);bind(buffers[0],0);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,actual);
                for(int offset=0;offset<BYTES;offset+=4) {
                    if(reference.getInt(offset)!=actual.getInt(offset) || !Float.isFinite(actual.getFloat(offset)))
                        throw new AssertionError("map wrap mismatch cohort="+cohort+" word="+offset/4
                            +" reference="+reference.getFloat(offset)+" actual="+actual.getFloat(offset));
                    ++words;
                }
            }
            System.out.println("PBR map wrap differential PASS: "+words+" exact finite channel words; signed pixel/index extremes, all mips/maps and invalid UV/LOD");
            if(Arrays.asList(args).contains("--benchmark")) {
                for(int i=0;i<2;++i){glDeleteProgram(programs[i]);programs[i]=program(source(i==0,false));}
                int query=glGenQueries();
                try {
                    for(int map=0;map<3;++map) {
                        inputs(input,map,false);bind(buffers[1],1);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,input);
                        dispatch(programs[0]);bind(buffers[0],0);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,reference);
                        dispatch(programs[1]);bind(buffers[0],0);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,actual);
                        for(int sample=0;sample<SAMPLES;++sample)for(int channel=0;channel<4;++channel) {
                            int offset=sample*48+channel*4;
                            if(reference.getInt(offset)!=actual.getInt(offset) || !Float.isFinite(actual.getFloat(offset)))
                                throw new AssertionError("Timed map shader mismatch map="+map+" sample="+sample+" channel="+channel);
                        }
                        for(int warm=0;warm<16;++warm){dispatch(programs[warm%2]);glFinish();}
                        long[][] times=new long[2][41];
                        for(int sample=0;sample<41;++sample)for(int order=0;order<2;++order){
                            int variant=(sample+order)%2;glUseProgram(programs[variant]);
                            glBeginQuery(GL_TIME_ELAPSED,query);glDispatchCompute(SAMPLES/64,1,1);glEndQuery(GL_TIME_ELAPSED);
                            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
                            times[variant][sample]=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                        }
                        Arrays.sort(times[0]);Arrays.sort(times[1]);
                        System.out.printf("PBR map paired compute map=%d samples=%d reference=%.3f us candidate=%.3f us change=%+.2f%% p95=%.3f/%.3f us%n",
                            map,SAMPLES,times[0][20]/1000.0,times[1][20]/1000.0,
                            100*(times[1][20]/(double)times[0][20]-1),times[0][38]/1000.0,times[1][38]/1000.0);
                    }
                }finally{glDeleteQueries(query);}
            }
            if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL error");
        }finally{
            MemoryUtil.memFree(input);MemoryUtil.memFree(reference);MemoryUtil.memFree(actual);
            if(context!=0){for(int p:programs)glDeleteProgram(p);for(int b:buffers)glDeleteBuffers(b);GL.setCapabilities(null);SDLVideo.SDL_GL_DestroyContext(context);}
            if(window!=0)SDLVideo.SDL_DestroyWindow(window);SDLInit.SDL_Quit();
        }
    }

    private static void inputs(ByteBuffer input,int cohort,boolean edges){
        Random random=new Random(187177+cohort);input.clear();
        int[] extremes={Integer.MIN_VALUE,Integer.MAX_VALUE,-33,-32,-31,-1,0,1,31,32,33};
        float[] nonfinite={Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.MAX_VALUE,-Float.MAX_VALUE};
        for(int i=0;i<SAMPLES;++i){
            int slot=edges && i%5==0 ? extremes[i%extremes.length] : i%32;
            int map=edges ? i%5-1 : cohort;
            int level=edges ? (i/5)%10-2 : i%6;
            float lod=edges && i%7==0 ? nonfinite[i%nonfinite.length] : (i%113)/19f;
            float u=edges && i%11==0 ? nonfinite[(i+1)%nonfinite.length] : random.nextFloat()*4-2;
            float v=edges && i%13==0 ? nonfinite[(i+2)%nonfinite.length] : random.nextFloat()*4-2;
            int x=edges && i%3==0 ? extremes[(i+cohort)%extremes.length] : random.nextInt();
            int y=edges && i%3==0 ? extremes[(i+cohort+3)%extremes.length] : random.nextInt();
            input.putInt(slot).putInt(map).putFloat(lod).putFloat(u).putFloat(v).putInt(level).putInt(x).putInt(y);
        }
        input.flip();
    }
    private static void bind(int buffer,int binding){glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,binding,buffer);}
    private static void dispatch(int program){glUseProgram(program);glDispatchCompute(SAMPLES/64,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);}
    private static int program(String source){
        int shader=glCreateShader(GL_COMPUTE_SHADER);glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE)throw new AssertionError(glGetShaderInfoLog(shader));
        int program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);glDeleteShader(shader);
        if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(program));return program;
    }
    static String source(boolean reference,boolean diagnostics)throws Exception{
        String maps;
        if(reference)try(var stream=VulkanPbrMapWrapGpuProbe.class.getResourceAsStream("/shaders/pbr_maps_reference.glsl")){
            if(stream==null)throw new IllegalStateException("Missing original map sampler");maps=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }else{String world=VulkanPbrWorldShader.SOURCE;maps=world.substring(world.indexOf("layout(std430, set = 3, binding = 7)"),world.indexOf("uint pbrVoxel("));}
        return """
            #version 450
            layout(local_size_x=64) in;
            layout(std430,binding=0) buffer Output { vec4 values[]; } outputData;
            layout(std430,binding=1) readonly buffer Input { vec4 values[]; } inputData;
            """+VulkanPbrShader.SOURCE+maps.replace("set = 3, ","")+"""
            void main(){
                uint lane=gl_GlobalInvocationID.x;
                vec4 a=inputData.values[lane*2u],b=inputData.values[lane*2u+1u];
                int slot=floatBitsToInt(a.x),map=floatBitsToInt(a.y),level=floatBitsToInt(b.y);
                vec2 uv=vec2(a.w,b.x);
                outputData.values[lane*3u]=pbrMap(slot,map,uv,a.z);
            """+(diagnostics?"""
                outputData.values[lane*3u+1u]=pbrMapLevel(slot,map,uv,level);
                outputData.values[lane*3u+2u]=pbrMapTexel(slot,map,ivec2(floatBitsToInt(b.z),floatBitsToInt(b.w)),level);
            """:"")+"}\n";
    }
}
