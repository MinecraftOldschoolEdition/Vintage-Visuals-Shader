package net.minecraft.src.graphics.backend.vulkan;

import java.nio.ByteOrder;
import java.util.Arrays;

/** Explicit native oracle for confirmed-water continuation across the nearby optical cube. */
public final class VulkanPbrWaterCoverageGpuProbe {
    private static final float[] ABSORPTION={.32f,.065f,.025f};
    private int assertions;

    public static void main(String[] args) throws Exception {
        new VulkanPbrWaterCoverageGpuProbe().run();
    }

    private void run() throws Exception {
        VulkanRayTracingGpuProbe gpu=new VulkanRayTracingGpuProbe();
        try {
            gpu.initialize();gpu.replaceProgram(source());
            volume(gpu,-24,false);columns(gpu,true);
            float[] x={-24.001f,-23.999f,0,23.999f,24.001f,-25,-23,25};
            float[] result=trace(gpu,x,-4,0,1,0,80);
            for(int lane=0;lane<8;++lane) beer(result,lane,3.985f,4,"Horizontal cube boundary");

            result=trace(gpu,x,-4,0,1,0,2);
            for(int lane=0;lane<8;++lane) beer(result,lane,2,3,"Finite submerged point-light segment");

            x=new float[]{.001f,23.001f,24.001f,25.001f,-23.999f,-24.999f,-25.999f,1.001f};
            result=trace(gpu,x,-4,.8f,.6f,0,80);
            for(int lane=0;lane<8;++lane) beer(result,lane,3.985f/.6f,9,"Oblique boundary continuation");

            // Looking from above can place the whole submerged floor below the 48-cube.
            volume(gpu,0,false);
            result=trace(gpu,new float[8],-4,0,1,0,80);
            for(int lane=0;lane<8;++lane) beer(result,lane,3.985f,4,"Vertical cube boundary");

            volume(gpu,-24,false);
            result=trace(gpu,new float[]{39,40,44,48,52,55,56,60},-4,0,1,0,80);
            for(int channel=0;channel<3;++channel) {
                for(int lane=1;lane<8;++lane)
                    check(result[lane*3+channel]>=result[(lane-1)*3+channel],"Outer coverage fades monotonically");
                near(1,result[6*3+channel],0,"No square-edge contribution beyond circular fade");
                near(1,result[7*3+channel],0,"No out-of-coverage attenuation");
            }

            columns(gpu,false);
            result=trace(gpu,new float[]{-25,-25,-25,25,25,25,30,30},-4,0,1,0,80);
            for(float value:result) near(1,value,0,"Unknown columns retain the existing fallback");

            columns(gpu,true);volume(gpu,-24,true);
            result=trace(gpu,new float[8],-4,0,1,0,80);
            for(float value:result) near(0,value,0,"Nearby opaque blocker still terminates sunlight");
            System.out.println("PBR water coverage native PASS: "+assertions+" assertions; cube boundaries, finite/oblique paths, fade, unknown data and opaque blockers");
        } finally {gpu.close();}
    }

    private static void volume(VulkanRayTracingGpuProbe gpu,int originY,boolean blocker) {
        gpu.materialData.write(0,VulkanPbrState.HEADER_BYTES,
            b->VulkanPbrState.writeHeader(b,-24,originY,-24,new float[8]));
        int[] cells=new int[VulkanPbrMaterialVolume.CELL_COUNT];
        for(int y=0;y<48;++y)for(int z=0;z<48;++z)for(int x=0;x<48;++x) {
            int worldY=originY+y;
            cells[x+48*(z+48*y)]=worldY>=-8&&worldY<0?202:0;
            if(blocker&&worldY==-2)cells[x+48*(z+48*y)]=192|4096;
        }
        VulkanPbrMaterialVolume.markEmptyBricks(cells);
        gpu.materialData.write(VulkanPbrState.VOLUME_OFFSET,VulkanPbrState.VOLUME_BYTES,
            b->b.order(ByteOrder.nativeOrder()).asIntBuffer().put(cells));
    }

    private static void columns(VulkanRayTracingGpuProbe gpu,boolean known) {
        gpu.materialData.write(VulkanPbrState.WATER_COLUMN_OFFSET,VulkanPbrWaterColumns.BYTES,b->{
            b.order(ByteOrder.nativeOrder()).putInt(-64).putInt(0).putInt(-64).putInt(128);
            int[] cells=new int[128*128];Arrays.fill(cells,known?(-8)&65535:0);b.asIntBuffer().put(cells);
        });
    }

    private static float[] trace(VulkanRayTracingGpuProbe gpu,float[] x,float y,float dx,float dy,float dz,float limit) throws Exception {
        float[] rays=new float[64];
        for(int lane=0;lane<8;++lane) {
            rays[lane*8]=x[lane];rays[lane*8+1]=y;rays[lane*8+3]=limit;
            rays[lane*8+4]=dx;rays[lane*8+5]=dy;rays[lane*8+6]=dz;
        }
        gpu.writeRayInputs(rays);gpu.dispatch(0,null,null,0,0,false,false);
        var output=gpu.results().order(ByteOrder.nativeOrder());float[] result=new float[24];
        for(int lane=0;lane<8;++lane)for(int channel=0;channel<3;++channel)
            result[lane*3+channel]=output.getFloat(lane*48+channel*4);
        return result;
    }

    static String source() {
        String world=VulkanPbrWorldShader.SOURCE;
        String body=world.substring(world.indexOf("layout(std430, set = 3, binding = 7)"),world.indexOf("float pbrFarSunVisibility("));
        return """
            #version 450
            layout(local_size_x=8) in;
            layout(std430,set=0,binding=1) readonly buffer Input { vec4 values[]; } inputData;
            layout(std430,set=0,binding=0) buffer Output { vec4 values[]; } outputData;
            struct ShadowProbe { vec4 cameraPosition; };
            const ShadowProbe shadowData=ShadowProbe(vec4(0));
            bool rtActive(){return false;}
            vec4 rtTransmission(vec3 p,vec3 n,vec3 d,float m,bool t,int q){return vec4(1);}
            int effectQuality(int effect){return 2;}
            """+VulkanPbrShader.SOURCE+body.replace("set = 3","set = 0")+"""
            void main(){uint lane=gl_GlobalInvocationID.x;vec4 a=inputData.values[lane*2u],b=inputData.values[lane*2u+1u];
                outputData.values[lane*3u]=pbrTrace(a.xyz,vec3(0,1,0),normalize(b.xyz),a.w,false);}
            """;
    }

    private void beer(float[] result,int lane,float distance,int segments,String label) {
        for(int channel=0;channel<3;++channel)
            near((float)(Math.exp(-ABSORPTION[channel]*distance)*Math.pow(.96,segments)),
                result[lane*3+channel],.00002f,label+" lane="+lane+" channel="+channel);
    }
    private void near(float expected,float actual,float tolerance,String label) {
        check(Float.isFinite(actual)&&Math.abs(expected-actual)<=tolerance,label+": expected="+expected+" actual="+actual);
    }
    private void check(boolean pass,String message) {++assertions;if(!pass)throw new AssertionError(message);}
}
