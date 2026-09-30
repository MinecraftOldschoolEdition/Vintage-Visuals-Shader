package net.minecraft.src.graphics.backend.vulkan;

import static org.lwjgl.opengl.GL45C.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;

/** Executes the production surface helper and verifies the actual water pixels in both modes. */
public final class VulkanPbrWaterCausticGpuProbe {
    private static final int LANES = 64;
    private static float encodedMean;
    private static VulkanWaterCausticTextureState.Animation animation;
    public static void main(String[] args) throws Exception {
        if (java.util.Arrays.asList(args).contains("--compile-only")) {
            ByteBuffer code = VulkanShaderCompiler.compileCompute(source(), "water-caustics.comp");
            try { System.out.println("Water caustic helper compile PASS: " + code.remaining()); }
            finally { MemoryUtil.memFree(code); }
            return;
        }
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException(SDLError.SDL_GetError());
        long window=0, context=0; int program=0, texture=0; int[] buffers=new int[3];
        ByteBuffer input=MemoryUtil.memCalloc(LANES*64).order(ByteOrder.nativeOrder());
        ByteBuffer output=MemoryUtil.memAlloc(LANES*16).order(ByteOrder.nativeOrder());
        try {
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION,4);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION,5);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
            window=SDLVideo.SDL_CreateWindow("Water caustic oracle",64,64,SDLVideo.SDL_WINDOW_OPENGL|SDLVideo.SDL_WINDOW_HIDDEN);
            context=SDLVideo.SDL_GL_CreateContext(window);
            if(window==0 || context==0 || !SDLVideo.SDL_GL_MakeCurrent(window,context)) throw new IllegalStateException(SDLError.SDL_GetError());
            GL.createCapabilities(); System.out.println("Water caustic GPU="+glGetString(GL_RENDERER));
            for(int i=0;i<3;++i) buffers[i]=glGenBuffers();
            bind(buffers[0],0);glBufferData(GL_SHADER_STORAGE_BUFFER,LANES*16,GL_DYNAMIC_READ);
            bind(buffers[1],1);glBufferData(GL_SHADER_STORAGE_BUFFER,input,GL_DYNAMIC_DRAW);
            ByteBuffer material=MemoryUtil.memCalloc(VulkanPbrState.STORAGE_BYTES).order(ByteOrder.nativeOrder());
            try {
                VulkanPbrState.writeHeader(material,0,0,0,new float[8]);
                material.putFloat(VulkanPbrState.PROFILE_OFFSET+10*80+8,1.333f);
                for(int y=4;y<24;++y)for(int z=4;z<44;++z)for(int x=4;x<44;++x) {
                    int cell=x+48*(z+48*y);
                    if(y<10 || x>=30) material.putInt(VulkanPbrState.VOLUME_OFFSET+cell*4,202);
                    if(y==10 && x>=20 && x<24) material.putInt(VulkanPbrState.VOLUME_OFFSET+cell*4,4096|192);
                }
                int tail=VulkanPbrState.VOLUME_OFFSET+VulkanPbrState.VOLUME_BYTES;
                material.putInt(tail,0).putInt(tail+4,0).putInt(tail+8,0).putInt(tail+12,128);
                for(int z=4;z<44;++z)for(int x=4;x<44;++x)
                    if(x<20 || x>=24)material.putInt(tail+16+(x+128*z)*4,x>=30?(40<<16)|8:(10<<16)|4);
                material.position(0);bind(buffers[2],7);glBufferData(GL_SHADER_STORAGE_BUFFER,material,GL_STATIC_DRAW);
            } finally { MemoryUtil.memFree(material); }
            Path asset=Path.of("resources/assets/minecraft/textures/environment/water_caustics.png");
            try(var image=java.nio.file.Files.newInputStream(asset);
                var metadata=java.nio.file.Files.newInputStream(Path.of(asset+".mcmeta"))) {
                animation=VulkanWaterCausticTextureState.decodeAnimation(image,metadata);
            }
            int side=animation.width(),frames=animation.layers();
            encodedMean=1+animation.meanIntensity();
            ByteBuffer pixels=MemoryUtil.memAlloc(animation.pixels().length);
            try {
                pixels.put(animation.pixels()).flip();texture=glGenTextures();glActiveTexture(GL_TEXTURE0+14);glBindTexture(GL_TEXTURE_2D_ARRAY,texture);
                glTexImage3D(GL_TEXTURE_2D_ARRAY,0,GL_R8,side,side,frames,0,GL_RED,GL_UNSIGNED_BYTE,pixels);
                glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
                glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_S,GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_T,GL_REPEAT);
            } finally { MemoryUtil.memFree(pixels); }
            int shader=glCreateShader(GL_COMPUTE_SHADER);glShaderSource(shader,source());glCompileShader(shader);
            if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE) throw new AssertionError(glGetShaderInfoLog(shader));
            program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);glDeleteShader(shader);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE) throw new AssertionError(glGetProgramInfoLog(program));
            int assertions=0; float largestAnimation=0;
            for(int hardware=0;hardware<2;++hardware) {
                float[] first=new float[LANES];
                float modeAnimation=0;
                for(int cohort=0;cohort<12;++cohort) {
                    for(int lane=0;lane<LANES;++lane) {
                        int o=lane*64;float x=12+(lane%8)*.5f,z=12+(lane/8)*.5f;
                        if(cohort==2)x+=8; if(cohort==3)x+=20; if(cohort==4)x=-4;
                        input.putFloat(o,x).putFloat(o+4,cohort==1?12:4).putFloat(o+8,z).putFloat(o+12,hardware);
                        input.putFloat(o+16,cohort==7?Float.NaN:1).putFloat(o+20,cohort==8?0:1);
                        input.putFloat(o+24,cohort==6?2:.01f).putFloat(o+28,cohort==9?33554432:0);
                        input.putFloat(o+32,cohort==5?64:cohort==11?256:0).putFloat(o+36,cohort==5?1:0);
                        input.putFloat(o+40,cohort==8?0:1).putFloat(o+44,cohort==10?0:encodedMean);
                        input.putFloat(o+48,Math.min(frames-1,8));
                    }
                    bind(buffers[1],1);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,input);
                    glUseProgram(program);glDispatchCompute(1,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
                    bind(buffers[0],0);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,output);
                    for(int lane=0;lane<LANES;++lane) {
                        float gain=output.getFloat(lane*16),depth=output.getFloat(lane*16+4);
                        if(!Float.isFinite(gain)||gain<.23f||gain>1.71f)throw new AssertionError("Unbounded gain="+gain);
                        if(cohort==0) {first[lane]=gain;if(depth!=6)throw new AssertionError("Wrong water depth="+depth);}
                        if(cohort==1||cohort==2||cohort==3||cohort==4||cohort==6||cohort==7||cohort==8||cohort==10)
                            if(gain!=1)throw new AssertionError("Dry/covered/unknown/deep/filtered/invalid/night receiver lit: "+cohort+" gain="+gain);
                        if(cohort==5)modeAnimation=Math.max(modeAnimation,Math.abs(gain-first[lane]));
                        if(cohort==9 && gain!=first[lane])throw new AssertionError("World-reference shift moved the pattern");
                        if(cohort==11 && gain!=first[lane])throw new AssertionError("Animation clock wrap moved the pattern");
                        assertions+=3;
                    }
                }
                if(modeAnimation<.01f)throw new AssertionError("Caustics did not animate in mode "+hardware);
                largestAnimation=Math.max(largestAnimation,modeAnimation);
            }
            if(largestAnimation<.01f)throw new AssertionError("Caustics did not animate");
            if (java.util.Arrays.asList(args).contains("--coverage-check"))
                assertions += coverageChecks(program, buffers, input, output) + depthChecks(program, buffers, input, output)
                    + patternChecks(program, buffers, input, output);
            if (java.util.Arrays.asList(args).contains("--integration-check"))
                assertions += integrationChecks(program,buffers,input,output);
            if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL error");
            System.out.println("Water caustic GPU PASS: "+assertions+" assertions; largest animation delta="+largestAnimation);
        } finally {
            MemoryUtil.memFree(input);MemoryUtil.memFree(output);
            if(context!=0) {glDeleteProgram(program);glDeleteTextures(texture);for(int buffer:buffers)glDeleteBuffers(buffer);GL.setCapabilities(null);SDLVideo.SDL_GL_DestroyContext(context);}
            if(window!=0)SDLVideo.SDL_DestroyWindow(window);SDLInit.SDL_Quit();
        }
    }
    private static int coverageChecks(int program, int[] buffers, ByteBuffer input, ByteBuffer output) {
        float[][] reference = new float[2][LANES];
        int assertions = 0;
        for (int cameraStep = 0; cameraStep < 4; ++cameraStep) {
            float cameraX = cameraStep < 2 ? 7.999f : 8.001f;
            float cameraY = cameraStep % 2 == 0 ? 5 : 40;
            ByteBuffer material = MemoryUtil.memCalloc(VulkanPbrState.STORAGE_BYTES).order(ByteOrder.nativeOrder());
            try {
                int ox=VulkanPbrMaterialVolume.originFor(cameraX), oy=VulkanPbrMaterialVolume.originFor(cameraY), oz=-24;
                VulkanPbrState.writeHeader(material, ox, oy, oz, new float[8]);
                material.putFloat(VulkanPbrState.PROFILE_OFFSET+10*80+8,1.333f);
                for(int y=0;y<48;++y) for(int z=0;z<48;++z) for(int x=0;x<48;++x)
                    if(y+oy>=4 && y+oy<10)
                        material.putInt(VulkanPbrState.VOLUME_OFFSET+(x+48*(z+48*y))*4,202);
                writeCoverageColumns(material,cameraX,0,0,4,10);
                material.position(0);
                bind(buffers[2],7); glBufferData(GL_SHADER_STORAGE_BUFFER,material,GL_STATIC_DRAW);
            } finally {MemoryUtil.memFree(material);}
            for (int mode=0;mode<2;++mode) {
                for(int lane=0;lane<LANES;++lane) {
                    int o=lane*64;
                    for(int j=0;j<64;j+=4) input.putFloat(o+j,0);
                    input.putFloat(o,28+(lane%8)*.5f).putFloat(o+4,4).putFloat(o+8,(lane/8)*.5f).putFloat(o+12,mode);
                    input.putFloat(o+16,1).putFloat(o+20,1).putFloat(o+24,.01f);
                    input.putFloat(o+40,1).putFloat(o+44,encodedMean);
                    input.putFloat(o+52,cameraX).putFloat(o+56,cameraY);
                }
                bind(buffers[1],1);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,input);
                glUseProgram(program);glDispatchCompute(1,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
                bind(buffers[0],0);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,output);
                float variation=0;
                for(int lane=0;lane<LANES;++lane) {
                    float gain=output.getFloat(lane*16), depth=output.getFloat(lane*16+4);
                    if(depth!=6) throw new AssertionError("Exposed water loses coverage only 20 blocks ahead: camera="+cameraX+","+cameraY+" depth="+depth);
                    variation=Math.max(variation,Math.abs(gain-1));
                    if(cameraStep==0) reference[mode][lane]=gain;
                    else if(Math.abs(gain-reference[mode][lane])>1e-6f)
                        throw new AssertionError("Camera anchor/altitude moved a stationary caustic: "+gain+" != "+reference[mode][lane]);
                    assertions+=2;
                }
                if(variation<.01f) throw new AssertionError("Caustics disappeared on confirmed distant water");
            }
        }
        return assertions;
    }

    private static int depthChecks(int program,int[] buffers,ByteBuffer input,ByteBuffer output) {
        ByteBuffer material=MemoryUtil.memCalloc(VulkanPbrState.STORAGE_BYTES).order(ByteOrder.nativeOrder());
        try {
            VulkanPbrState.writeHeader(material,-24,0,-24,new float[8]);
            material.putFloat(VulkanPbrState.PROFILE_OFFSET+10*80+8,1.333f);
            writeCoverageColumns(material,0,0,0,8,40);
            material.position(0);bind(buffers[2],7);glBufferData(GL_SHADER_STORAGE_BUFFER,material,GL_STATIC_DRAW);
        } finally {MemoryUtil.memFree(material);}
        float[] depths={11.99f,12.01f,20,24,31.99f,32.01f};
        int assertions=0;
        for(int mode=0;mode<2;++mode) {
            float[] before=new float[LANES];
            for(int step=0;step<depths.length;++step) {
                for(int lane=0;lane<LANES;++lane) {
                    int o=lane*64;for(int j=0;j<64;j+=4)input.putFloat(o+j,0);
                    input.putFloat(o,12+(lane%8)*.5f).putFloat(o+4,40-depths[step]).putFloat(o+8,(lane/8)*.5f).putFloat(o+12,mode);
                    input.putFloat(o+16,1).putFloat(o+20,1).putFloat(o+24,.01f).putFloat(o+40,1000).putFloat(o+44,encodedMean);
                }
                bind(buffers[1],1);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,input);glUseProgram(program);
                glDispatchCompute(1,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
                bind(buffers[0],0);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,output);
                float variation=0;
                for(int lane=0;lane<LANES;++lane) {
                    float gain=output.getFloat(lane*16);variation=Math.max(variation,Math.abs(gain-1));
                    if(step==0)before[lane]=gain;
                    if(step==1 && Math.abs(gain-before[lane])>.025f)throw new AssertionError("Twelve-block depth step: "+gain+" vs "+before[lane]);
                    if(step>=4 && Math.abs(gain-1)>1e-5f)throw new AssertionError("Deep end did not smoothly approach zero: "+gain);
                    assertions++;
                }
                if(step<4 && variation<.01f)throw new AssertionError("Caustic prematurely absent at depth "+depths[step]+" mode="+mode);
            }
        }
        return assertions;
    }

    private static int patternChecks(int program,int[] buffers,ByteBuffer input,ByteBuffer output) {
        int side=animation.width(),frameSize=side*side,assertions=0;
        int nextFrame=Math.min(animation.layers()-1,8);
        for(int mode=0;mode<2;++mode)for(float blend:new float[]{0,.25f,1})for(int start=0;start<frameSize;start+=LANES) {
            for(int lane=0;lane<LANES;++lane) {
                int pixel=(start+lane)%frameSize,o=lane*64;
                for(int j=0;j<64;j+=4)input.putFloat(o+j,0);
                input.putFloat(o,((pixel%side)+.5f)/side).putFloat(o+8,((pixel/side)+.5f)/side).putFloat(o+12,mode);
                input.putFloat(o+16,1).putFloat(o+36,blend).putFloat(o+40,1).putFloat(o+44,encodedMean).putFloat(o+48,nextFrame);
            }
            bind(buffers[1],1);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,input);glUseProgram(program);
            glDispatchCompute(1,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
            bind(buffers[0],0);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,output);
            for(int lane=0;lane<LANES && start+lane<frameSize;++lane) {
                int pixel=start+lane;
                float first=(animation.pixels()[pixel]&255)/255f,next=(animation.pixels()[nextFrame*frameSize+pixel]&255)/255f;
                float expected=first+(next-first)*blend,actual=output.getFloat(lane*16+8);
                if(Math.abs(actual-expected)>2e-6f)throw new AssertionError("Water texel/animation mismatch: mode="+mode+" pixel="+pixel+" blend="+blend+" actual="+actual+" expected="+expected);
                ++assertions;
            }
        }
        return assertions;
    }

    private static int integrationChecks(int program,int[] buffers,ByteBuffer input,ByteBuffer output) {
        ByteBuffer material=MemoryUtil.memCalloc(VulkanPbrState.STORAGE_BYTES).order(ByteOrder.nativeOrder());
        try {
            VulkanPbrState.writeHeader(material,-24,-24,-24,new float[8]);
            net.minecraft.src.graphics.api.TerrainMaterialProfiles.defaults().writeGpuTable(
                material.duplicate().position(VulkanPbrState.PROFILE_OFFSET).slice().order(ByteOrder.nativeOrder()).asFloatBuffer());
            for(int y=4;y<10;++y)for(int z=0;z<48;++z)for(int x=0;x<48;++x)
                material.putInt(VulkanPbrState.VOLUME_OFFSET+(x+48*(z+48*(y+24)))*4,202);
            writeCoverageColumns(material,0,0,0,4,10);
            material.position(0);bind(buffers[2],7);glBufferData(GL_SHADER_STORAGE_BUFFER,material,GL_STATIC_DRAW);
        } finally {MemoryUtil.memFree(material);}
        int assertions=0;
        for(int mode=0;mode<2;++mode)for(int tag:new int[]{208,192,199,195,194,204,196,200,201,202}) {
            for(int lane=0;lane<LANES;++lane) {
                int o=lane*64;for(int j=0;j<64;j+=4)input.putFloat(o+j,0);
                input.putFloat(o,((lane%8)+.5f)/8).putFloat(o+4,4).putFloat(o+8,((lane/8)+.5f)/8).putFloat(o+12,mode);
                input.putFloat(o+16,1).putFloat(o+20,-1).putFloat(o+24,.001f);
                input.putFloat(o+32,tag).putFloat(o+40,1).putFloat(o+44,encodedMean).putFloat(o+48,8);
            }
            bind(buffers[1],1);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,input);glUseProgram(program);
            glDispatchCompute(1,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
            bind(buffers[0],0);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,output);
            float maximumDifference=0;
            for(int lane=0;lane<LANES;++lane) {
                float unmodulated=output.getFloat(lane*16),delta=output.getFloat(lane*16+12);
                if(unmodulated<=0)throw new AssertionError("Default material receives no test sunlight: tag="+tag);
                maximumDifference=Math.max(maximumDifference,delta);assertions++;
            }
            boolean solid=tag!=196 && tag!=200 && tag!=201 && tag!=202;
            if(solid && maximumDifference<.0001f)throw new AssertionError("Actual PBR sun block suppresses caustics on default solid material tag="+tag+" mode="+mode+" delta="+maximumDifference);
            if(!solid && maximumDifference!=0)throw new AssertionError("Caustics modulate a sheet/fluid material: tag="+tag+" delta="+maximumDifference);
        }
        return assertions;
    }

    private static void writeCoverageColumns(ByteBuffer material,float cameraX,float cameraZ,int referenceY,int bottom,int surface) {
        // The old production allocation intentionally has no column tail: this is the red case.
        int offset=VulkanPbrState.VOLUME_OFFSET+VulkanPbrState.VOLUME_BYTES;
        if(material.capacity()<offset+16+128*128*4) return;
        int ox=Math.floorDiv((int)Math.floor(cameraX),8)*8-64;
        int oz=Math.floorDiv((int)Math.floor(cameraZ),8)*8-64;
        material.putInt(offset,ox).putInt(offset+4,referenceY).putInt(offset+8,oz).putInt(offset+12,128);
        for(int i=0;i<128*128;++i)material.putInt(offset+16+i*4,(surface<<16)|(bottom&65535));
    }

    private static void bind(int buffer,int binding) {glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,binding,buffer);}

    static String source() {
        String world=VulkanPbrWorldShader.SOURCE;
        String header=world.substring(world.indexOf("layout(std430, set = 3, binding = 7)"),world.indexOf("vec4 pbrMapTexel("));
        String voxel=world.substring(world.indexOf("uint pbrVoxel("),world.indexOf("float pbrFarSunVisibility("));
        String sunBlock=world.substring(world.indexOf("vec3 sun = shadowData.sunDirection.xyz;",world.indexOf("vec3 pbrShadeSurface(")),world.indexOf("float localSunlight ="));
        int thinStart=world.indexOf("bool thin =",world.indexOf("vec3 pbrShadeSurface("));
        String thin=world.substring(thinStart,world.indexOf(';',thinStart)+1);
        return """
            #version 450
            layout(local_size_x=64) in;
            layout(std430,binding=0) buffer Output { vec4 values[]; } outputData;
            layout(std430,binding=1) readonly buffer Input { vec4 values[]; } inputData;
            struct ShadowData { vec4 worldReference; vec4 causticAnimation; vec4 cameraPosition; vec4 sunDirection; vec4 emissiveMeta; }; ShadowData shadowData;
            bool hardware; float probeClock;
            bool rtActive() { return hardware; }
            int effectQuality(int effect) { return 2; }
            vec4 rtTransmission(vec3 p,vec3 n,vec3 d,float distance,bool thin,int quality) { return vec4(.7,.8,.9,0); }
            vec3 rtSunTransmission(vec3 p,vec3 n,bool thin,int quality) { return vec3(.7,.8,.9); }
            float pbrFarSunVisibility(vec3 p,vec3 light,float sheet,float mapped) { return mapped; }
            """+VulkanPbrShader.SOURCE+header.replace("set = 3, ","")+voxel
            +VulkanPbrWaterCausticShader.SOURCE.replace("set=3,","").replace("pbrData.ground.w","probeClock")+"""
            vec3 productionSun(float tag,vec3 position,vec3 normal,vec3 positionDx,vec3 positionDy) {
                PbrMaterial m=pbrData.profiles[int(tag)-192];
                vec3 n=normal,geometricNormal=normal,view=normal,albedo=vec3(.4,.3,.2),radiance=vec3(0);
                float thickness=m.absorption.w,sunVisibility=1.0,cloudVisibility=1.0;
            """+thin+sunBlock+"""
                return radiance;
            }
            void main() {
                uint lane=gl_GlobalInvocationID.x;
                vec4 a=inputData.values[lane*4u],b=inputData.values[lane*4u+1u],c=inputData.values[lane*4u+2u];
                hardware=a.w>.5;probeClock=c.x;
                shadowData.worldReference=vec4(b.w,0,b.w,0);
                shadowData.cameraPosition=vec4(inputData.values[lane*4u+3u].yzw,1);
                shadowData.causticAnimation=vec4(0,inputData.values[lane*4u+3u].x,c.y,c.w);
                vec3 normal=vec3(0,b.x,0),sun=normalize(vec3(.2,c.z,.1));
                if(b.y<0.0) {
                    shadowData.sunDirection=vec4(0,1,0,0);shadowData.emissiveMeta=vec4(0,0,0,1);
                    shadowData.causticAnimation.w=0;
                    vec3 off=productionSun(c.x,a.xyz,normal,vec3(b.z,0,0),vec3(0,0,b.z));
                    shadowData.causticAnimation.w=c.w;
                    vec3 on=productionSun(c.x,a.xyz,normal,vec3(b.z,0,0),vec3(0,0,b.z));
                    outputData.values[lane]=vec4(off,max(max(abs(on.r-off.r),abs(on.g-off.g)),abs(on.b-off.b)));
                    return;
                }
                float gain=pbrWaterCausticGain(a.xyz,normal,sun,vec3(b.z,0,0),vec3(0,0,b.z));
                float depth=any(isnan(normal)) ? 0.0 : pbrWaterDepth(a.xyz,normal);
                outputData.values[lane]=vec4(gain,depth,pbrWaterCausticPattern(a.xz,vec2(0),vec2(0)),1);
            }
            """;
    }
}
