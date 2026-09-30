package net.minecraft.src.graphics.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/** Explicit native cache proof; --compile-only never opens a Vulkan device. */
public final class VulkanHardwareIrradianceGpuProbe {
    private final VulkanRayTracingGpuProbe gpu=new VulkanRayTracingGpuProbe();
    private int assertions;
    private long revision;

    public static void main(String[] args) {
        if(args.length>0 && args[0].equals("--compile-only")) {
            for(String source:new String[]{computeSource(),samplingSource()}) {
                ByteBuffer code=VulkanShaderCompiler.compileCompute(source,"hardware-irradiance-native.comp");
                try {System.out.println("Native irradiance probe CPU compile PASS bytes="+code.remaining());}
                finally {MemoryUtil.memFree(code);}
            }
            return;
        }
        VulkanHardwareIrradianceGpuProbe probe=new VulkanHardwareIrradianceGpuProbe();
        try {
            probe.gpu.initialize();probe.verifyComputedRadiance();probe.verifyInterpolationAndWall();
            System.out.println("Hardware irradiance GPU PASS "+probe.assertions+" assertions: real SH compute, emitter/sky/opaque cases, cached interpolation, wall occlusion and disable");
        } finally {probe.gpu.close();}
    }

    private void verifyComputedRadiance() {
        this.gpu.replaceProgram(computeSource());
        this.gpu.globalShadowData.write(0,VulkanRayTracingGpuProbe.GLOBAL_SHADOW_BYTES,bytes->{
            bytes.order(ByteOrder.nativeOrder());while(bytes.hasRemaining())bytes.put((byte)0);
            bytes.putFloat(3632+12,1); // Complete production ShadowData.materialParams.w.
        });
        float[] room={-3,-3,-3,-2,3,3, 2,-3,-3,3,3,3, -3,-3,-3,3,-2,3,
            -3,2,-3,3,3,3, -3,-3,-3,3,3,-2, -3,-3,2,3,3,3};
        for(int quality=0;quality<4;++quality) {
            reset(1,quality);
            scene(room,materials(6,203|15<<8,0xff6010));
            ByteBuffer result=data();int record=VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS*4;
            near(2,result.getFloat(record+12),"open 2-block AO visibility");
            for(int color=0;color<3;++color) {
                float expected=linear(new float[]{1,96f/255,16f/255}[color]);
                near(expected,result.getFloat(record+16+color*16),"constant emissive room SH mean");
                near(0,result.getFloat(record+64+color*16),"enclosed room has no sky");
            }
            reset(1,quality);scene(room,materials(6,192|4096,0xffffff));
            for(int channel=4;channel<28;++channel) near(0,data().getFloat(record+channel*4),"opaque unlit scene clears old emission");
        }
        float[] closeRoom=room.clone();
        for(int i=0;i<closeRoom.length;++i)closeRoom[i]*=.125f;
        reset(1,3);scene(closeRoom,materials(6,192|4096,0xffffff));
        float expectedAo=0;
        for(int i=0;i<32;++i) {
            double y=1-2*(i+.5)/32,radial=Math.sqrt(1-y*y),angle=i*2.39996322973;
            double distance=.25/Math.max(Math.abs(y),Math.max(Math.abs(radial*Math.cos(angle)),Math.abs(radial*Math.sin(angle))));
            double fraction=distance/2;expectedAo+=(float)(fraction*fraction*(3-2*fraction)/32);
        }
        near(1+expectedAo,data().getFloat(VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS*4+12),
            "nearby opaque geometry produces ray-derived cached AO");
        this.gpu.materialData.write(16,32,bytes->bytes.order(ByteOrder.nativeOrder())
            .putFloat(.1f).putFloat(.2f).putFloat(.4f).putFloat(1)
            .putFloat(.1f).putFloat(.2f).putFloat(.4f).putFloat(0));
        reset(1,2);scene(new float[]{100,100,100,101,101,101},materials(1,192|4096,0xffffff));
        int record=VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS*4;
        for(int color=0;color<3;++color) {
            near(0,data().getFloat(record+16+color*16),"open scene hit irradiance zero");
            near(new float[]{.1f,.2f,.4f}[color],data().getFloat(record+64+color*16),"sky irradiance without emitters");
        }
        this.gpu.materialData.write(16,32,bytes->{while(bytes.hasRemaining())bytes.put((byte)0);});
    }

    private void verifyInterpolationAndWall() {
        this.gpu.replaceProgram(samplingSource());
        this.gpu.materialData.write(16,32,bytes->{
            bytes.order(ByteOrder.nativeOrder());
            for(int vector=0;vector<2;++vector)bytes.putFloat(1f/(float)Math.PI).putFloat(1f/(float)Math.PI)
                .putFloat(1f/(float)Math.PI).putFloat(0);
        });
        reset(64,2);
        this.gpu.irradianceData.write(0,VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES,bytes->{
            bytes.order(ByteOrder.nativeOrder());
            bytes.putInt(VulkanHardwareIrradianceVolume.HEADER_WORDS*4,1);
            for(int y=0;y<4;++y)for(int z=0;z<4;++z)for(int x=0;x<4;++x) {
                int at=(VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS+(x+4*(z+4*y))*28)*4;
                bytes.putFloat(at,x*4+.5f).putFloat(at+4,y*4+.5f).putFloat(at+8,z*4+.5f).putFloat(at+12,2);
                bytes.putFloat(at+16,x==0?1:0);
            }
        });
        float[] inputs=new float[64];
        for(int ray=0;ray<8;++ray) {
            inputs[ray*8]=3.5f;inputs[ray*8+1]=4.5f;inputs[ray*8+2]=4.5f;inputs[ray*8+4]=1;
        }
        this.gpu.writeRayInputs(inputs);this.gpu.dispatch(0,null,null,0,0,false,false);
        float expected=(1-(3.5f+.51f-.5f)/4)*(float)Math.PI;
        near(expected,this.gpu.results().getFloat(0),"trilinear world-space irradiance");
        near(1,this.gpu.results().getFloat(4),"cached AO open");
        near(0,this.gpu.results().getFloat(8),"ready cache skips all fallback environment and voxel evaluations");
        if(this.gpu.results().getFloat(12)<=0)throw new AssertionError("uncertified fixture must exercise DDA");
        this.gpu.irradianceData.write((VulkanHardwareIrradianceVolume.OPACITY_OFFSET_WORDS+512)*4L,64*4,
            bytes->{for(int i=0;i<64;++i)bytes.putInt(0x01666666);});
        this.gpu.dispatch(0,null,null,0,0,false,false);
        near(expected,this.gpu.results().getFloat(0),"certified clear box preserves exact irradiance");
        near(1,this.gpu.results().getFloat(4),"certified clear box preserves exact AO");
        near(0,this.gpu.results().getFloat(12),"clear certificates skip every visibility DDA");
        // PBR can consume cached AO while Colored Global Illumination itself is disabled.
        this.gpu.coloredData.write(0,64,bytes->{while(bytes.hasRemaining())bytes.put((byte)0);});
        this.gpu.dispatch(0,null,null,0,0,false,false);
        near(1,this.gpu.results().getFloat(4),"PBR-only cache survives disabled colored GI header");
        this.gpu.irradianceData.write(VulkanHardwareIrradianceVolume.OPACITY_OFFSET_WORDS*4L,512*4,bytes->{
            bytes.order(ByteOrder.nativeOrder());
            for(int y=0;y<16;++y)for(int z=0;z<16;++z) {
                int cell=2+16*(z+16*y),offset=(cell>>3)*4;
                bytes.putInt(offset,bytes.getInt(offset)|15<<((cell&7)*4));
            }
        });
        this.gpu.irradianceData.write((VulkanHardwareIrradianceVolume.OPACITY_OFFSET_WORDS+512)*4L,64*4,
            bytes->{for(int i=0;i<64;++i)bytes.putInt(1<<24);});
        this.gpu.dispatch(0,null,null,0,0,false,false);
        near(0,this.gpu.results().getFloat(0),"opaque wall rejects irradiance on the other side");
        if(this.gpu.results().getFloat(12)<=0)throw new AssertionError("outside certified box must retain wall DDA");
        this.gpu.irradianceData.write(4,4,bytes->bytes.putInt(0));
        this.gpu.dispatch(0,null,null,0,0,false,false);
        near(0,this.gpu.results().getFloat(0),"disabled cache fallback has no fake hardware radiance");
        near(4,this.gpu.results().getFloat(8),"disabled cache evaluates fallback only once across AO and GI");
        for(float exposure:new float[]{0,.25f,1}) {
            for(int ray=0;ray<8;++ray) {inputs[ray*8+3]=exposure;inputs[ray*8+7]=1;}
            this.gpu.writeRayInputs(inputs);this.gpu.dispatch(0,null,null,0,0,false,false);
            near(exposure,this.gpu.results().getFloat(0),"missing cache sky follows receiver exposure");
            near(5,this.gpu.results().getFloat(8),"sky fallback evaluated only when requested");
        }
    }

    private void reset(int count,int quality) {
        this.gpu.irradianceData.write(0,VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES,bytes->{
            bytes.order(ByteOrder.nativeOrder());while(bytes.hasRemaining())bytes.put((byte)0);
            bytes.position(0);bytes.putInt(count).putInt(1).putInt(0).putInt(count);
            bytes.putInt(0).putInt(0).putInt(0).putInt(4);
            bytes.putInt(1).putInt(1).putInt(1).putInt(VulkanHardwareIrradianceVolume.rayCountFor(quality));
        });
    }
    private void scene(float[] boxes,int[] materials) { this.gpu.dispatch(0,boxes,materials,boxes.length/6,++this.revision,true,false); }
    private ByteBuffer data() {return MemoryUtil.memByteBuffer(this.gpu.irradianceData.mappedAddress(),VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES).order(ByteOrder.nativeOrder());}
    private static int[] materials(int count,int optics,int tint) {
        int[] values=new int[count*8];for(int primitive=0;primitive<count;++primitive) {
            for(int side=0;side<6;++side)values[primitive*8+side]=1;
            values[primitive*8+6]=tint;values[primitive*8+7]=optics;
        }return values;
    }
    private static float linear(float value) {return value<=.04045f?value/12.92f:(float)Math.pow((value+.055)/1.055,2.4);}
    private void near(float expected,float actual,String label) {
        if(!Float.isFinite(actual)||Math.abs(expected-actual)>.0005f)throw new AssertionError(label+": "+expected+" != "+actual);
        ++this.assertions;
    }
    static String computeSource() {
        return VulkanHardwareIrradianceShader.computeSource().replace("layout(set=3,binding=0)","layout(set=3,binding=14)")
            .replace("set=3","set=0").replace("set = 3","set = 0");
    }
    private static String samplingSource() {
        String world=VulkanPbrWorldShader.SOURCE;
        String header=world.substring(world.indexOf("layout(std430, set = 3, binding = 7)"),world.indexOf("vec4 pbrMapTexel("));
        String environment=world.substring(world.indexOf("vec3 pbrEnvironment("),world.indexOf("vec2 pbrEnvironmentBrdf("))
            .replace("pbrDiffuseIrradiance(","probeDiffuseIrradiance(");
        return """
            #version 460
            layout(local_size_x=8) in;
            layout(std430,set=0,binding=0) buffer Results {vec4 values[];} outputData;
            layout(std430,set=0,binding=1) readonly buffer Inputs {vec4 inputs[];} rayData;
            """+VulkanPbrShader.SOURCE+VulkanColoredLightShader.SOURCE.replace("set = 3","set = 0")
            +header.replace("set = 3","set = 0")+environment+"""
            int fallbackEvaluationCount=0,visibilityWalks=0;
            vec3 pbrDiffuseIrradiance(vec3 n,float e){++fallbackEvaluationCount;return probeDiffuseIrradiance(n,e);}
            uint pbrVoxelAt(vec3 p){++fallbackEvaluationCount;return 0u;}
            """+VulkanHardwareIrradianceShader.fragmentSource().replace("set=3","set=0")
            .replace("bool hwGiVisible(vec3 from,vec3 to) {","bool hwGiVisible(vec3 from,vec3 to) {++visibilityWalks;")+"""
            void main(){uint lane=gl_GlobalInvocationID.x;
                vec3 p=rayData.inputs[lane*2].xyz,n=rayData.inputs[lane*2+1].xyz;
                float ao=rtCachedAmbientOcclusion(p,n);
                vec3 irradiance=rtCachedIrradiance(p,n,rayData.inputs[lane*2+1].w>.5,rayData.inputs[lane*2].w);
                outputData.values[lane*3]=vec4(irradiance.r,ao,float(fallbackEvaluationCount),float(visibilityWalks));
            }
            """;
    }
}
