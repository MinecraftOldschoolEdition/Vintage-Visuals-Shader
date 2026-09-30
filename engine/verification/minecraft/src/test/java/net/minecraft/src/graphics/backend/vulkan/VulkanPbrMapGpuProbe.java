package net.minecraft.src.graphics.backend.vulkan;

import java.nio.ByteOrder;
import net.minecraft.src.graphics.api.TerrainMaterialProfiles;

/** Exercises the production map samplers with invalid gradients, coordinates and integer indices. */
public final class VulkanPbrMapGpuProbe {
    private record Input(int slot, int map, float lod, float u, float v, int level, int x, int y) { }
    private static final Input[] INPUTS = {
        new Input(0,0,Float.NaN,Float.NaN,Float.POSITIVE_INFINITY,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE),
        new Input(31,1,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NaN,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE),
        new Input(Integer.MIN_VALUE,Integer.MIN_VALUE,Float.NEGATIVE_INFINITY,Float.MAX_VALUE,-Float.MAX_VALUE,-1,-500,500),
        new Input(Integer.MAX_VALUE,Integer.MAX_VALUE,99,1000.25f,-1000.75f,99,Integer.MIN_VALUE,Integer.MAX_VALUE),
        new Input(16,0,-100,.25f,.75f,0,0,0),
        new Input(4,1,2.5f,Float.NaN,Float.NEGATIVE_INFINITY,2,7,-17),
        new Input(17,2,4.75f,.4f,.6f,4,-100,100),
        new Input(7,-1,0,Float.MIN_VALUE,-Float.MIN_VALUE,5,-1,1)
    };

    public static void main(String[] args) {
        VulkanRayTracingGpuProbe fixture = new VulkanRayTracingGpuProbe();
        try {
            fixture.initialize();
            fixture.materialData.write(VulkanPbrState.MAP_OFFSET,
                TerrainMaterialProfiles.MAP_TEXEL_COUNT * Integer.BYTES, bytes -> {
                    bytes.order(ByteOrder.nativeOrder());
                    for (int slot=0;slot<32;++slot) for (int map=0;map<3;++map) for (int level=0;level<6;++level) {
                        int size=32>>level;
                        int rgba=(slot*7)|((map*80)<<8)|((level*40)<<16)|0xff000000;
                        for (int pixel=0;pixel<size*size;++pixel) bytes.putInt(rgba);
                    }
                });
            float[] packed = new float[64];
            for (int lane=0;lane<INPUTS.length;++lane) {
                Input value=INPUTS[lane]; int offset=lane*8;
                packed[offset]=Float.intBitsToFloat(value.slot);
                packed[offset+1]=Float.intBitsToFloat(value.map);
                packed[offset+2]=value.lod; packed[offset+3]=value.u; packed[offset+4]=value.v;
                packed[offset+5]=Float.intBitsToFloat(value.level);
                packed[offset+6]=Float.intBitsToFloat(value.x);
                packed[offset+7]=Float.intBitsToFloat(value.y);
            }
            fixture.writeRayInputs(packed);
            fixture.replaceProgram(source());
            fixture.dispatch(0,null,null,0,0,false,false);
            int assertions=0;
            for (int lane=0;lane<INPUTS.length;++lane) {
                Input input=INPUTS[lane];
                float lod=Float.isNaN(input.lod)?0:Math.max(0,Math.min(5,input.lod));
                int lower=(int)Math.floor(lod), upper=Math.min(5,lower+1);
                for (int channel=0;channel<4;++channel) {
                    float a=expected(input,lower,channel),b=expected(input,upper,channel);
                    check(a+(b-a)*(lod-lower),fixture.results().getFloat(lane*48+channel*4),lane,"filtered",channel);
                    float direct=expected(input,Math.max(0,Math.min(5,input.level)),channel);
                    check(direct,fixture.results().getFloat(lane*48+16+channel*4),lane,"level",channel);
                    check(direct,fixture.results().getFloat(lane*48+32+channel*4),lane,"texel",channel);
                    assertions+=3;
                }
            }
            System.out.println("PBR map GPU PASS: "+assertions+" finite samples; NaN/Infinity LOD and UV, integer index extremes, bounded mip shifts and emission filtering");
        } finally {
            fixture.close();
        }
    }

    private static float expected(Input input,int level,int channel) {
        int slot=Math.max(0,Math.min(31,input.slot)),map=Math.max(0,Math.min(2,input.map));
        float value=(channel==0?slot*7:channel==1?map*80:channel==2?level*40:255)/255.0f;
        if (map==2 && channel<3) value=value<=.04045f?value/12.92f:(float)Math.pow((value+.055f)/1.055f,2.4);
        return value;
    }

    private static void check(float expected,float actual,int lane,String sampler,int channel) {
        if (!Float.isFinite(actual) || Math.abs(expected-actual)>0.0001f)
            throw new AssertionError("lane "+lane+" "+sampler+" channel "+channel+": "+actual+" expected "+expected);
    }

    private static String source() {
        String world=VulkanPbrWorldShader.SOURCE;
        String maps=world.substring(world.indexOf("layout(std430, set = 3, binding = 7)"),world.indexOf("uint pbrVoxel("));
        return """
            #version 450
            layout(local_size_x=8) in;
            layout(std430,set=0,binding=1) readonly buffer Input { vec4 values[]; } inputData;
            layout(std430,set=0,binding=0) buffer Output { vec4 values[]; } outputData;
            """+VulkanPbrShader.SOURCE+maps.replace("set = 3","set = 0")+"""
            void main() {
                uint lane=gl_GlobalInvocationID.x;
                vec4 a=inputData.values[lane*2u],b=inputData.values[lane*2u+1u];
                int slot=floatBitsToInt(a.x),map=floatBitsToInt(a.y),level=floatBitsToInt(b.y);
                vec2 uv=vec2(a.w,b.x);
                outputData.values[lane*3u]=pbrMap(slot,map,uv,a.z);
                outputData.values[lane*3u+1u]=pbrMapLevel(slot,map,uv,level);
                outputData.values[lane*3u+2u]=pbrMapTexel(slot,map,ivec2(floatBitsToInt(b.z),floatBitsToInt(b.w)),level);
            }
            """;
    }
}
