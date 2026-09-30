package net.minecraft.src.graphics.backend.vulkan;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.src.world.core.LightweightTestWorld;
import org.lwjgl.system.MemoryUtil;

/** Completed native SH survives residency changes and still converges to a fresh full update. */
public final class VulkanHardwareIrradianceMigrationGpuProbe {
    public static void main(String[] args) throws Exception {
        VulkanRayTracingGpuProbe gpu = new VulkanRayTracingGpuProbe();
        try {
            gpu.initialize();
            VulkanRuntime runtime = (VulkanRuntime)get(gpu, "runtime");
            VulkanHardwareIrradianceState state = new VulkanHardwareIrradianceState(runtime);
            set(state,"buffers",new VulkanMappedBuffer[]{gpu.irradianceData});
            set(state,"revisions",new long[1]);set(state,"environments",new long[1]);
            set(state,"qualities",new int[1]);set(state,"cursors",new int[1]);
            set(state,"pendingCounts",new int[1]);set(state,"probeCounts",new int[1]);
            set(state,"initialized",new boolean[1]);set(state,"layouts",new int[1][]);
            set(state,"worldIdentities",new Object[1]);
            Method finish = VulkanHardwareIrradianceState.class.getDeclaredMethod("finishDispatch",int.class);
            finish.setAccessible(true);
            gpu.replaceProgram(VulkanHardwareIrradianceGpuProbe.computeSource());
            gpu.globalShadowData.write(0,VulkanRayTracingGpuProbe.GLOBAL_SHADOW_BYTES,bytes -> {
                bytes.order(ByteOrder.nativeOrder());while(bytes.hasRemaining())bytes.put((byte)0);
                bytes.putFloat(3632+12,1);
            });
            LightweightTestWorld world = new LightweightTestWorld("native-irradiance-migration");
            for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++)world.prepareTestChunk(x,z).hasChunkData=true;
            VulkanHardwareIrradianceVolume volume = new VulkanHardwareIrradianceVolume();
            float[] terrain={8,63,8,9,64,9,24,63,8,25,64,9};
            volume.update(world,terrain,0,0,0,null,0,0,0,0,15.9f,64,8);
            sky(gpu,.1f,.2f,.4f);
            state.prepareFrame(volume,0,1,true);
            // A remote primitive keeps a real, nonempty TLAS while every fixture probe sees open sky.
            int[] materials=new int[8];materials[7]=192|4096;
            gpu.dispatch(0,new float[]{1000,1000,1000,1001,1001,1001},materials,1,1,true,false);
            refresh(gpu,state,volume,finish);
            int[] oldLayout=volume.words(),oldResults=results(gpu,oldLayout[0]);
            volume.update(world,terrain,0,0,0,null,0,64,-64,64,16.1f,64,8);
            state.prepareFrame(volume,0,1,true);
            int[] migrated=results(gpu,volume.probeCount());
            int preserved=0;
            for(int probe=0;probe<volume.probeCount();probe++) {
                int record=VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS+probe*28;
                if(Float.intBitsToFloat(migrated[probe*28+3])<.5f)continue;
                int previous=-1;
                for(int candidate=0;candidate<oldLayout[0];candidate++) {
                    int at=VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS+candidate*28;
                    boolean equal=true;
                    for(int axis=0;axis<3;axis++)equal &= (double)Float.intBitsToFloat(oldLayout[at+axis])+oldLayout[12+axis]
                        == (double)Float.intBitsToFloat(volume.words()[record+axis])+volume.words()[12+axis];
                    if(equal){previous=candidate;break;}
                }
                if(previous<0)throw new AssertionError("Migrated unknown probe");
                for(int channel=3;channel<28;channel++)if(oldResults[previous*28+channel]!=migrated[probe*28+channel])
                    throw new AssertionError("Changed migrated result probe="+probe+" channel="+channel);
                preserved++;
            }
            if(preserved<512)throw new AssertionError("Too few overlapping completed probes retained: "+preserved);
            sky(gpu,.7f,.2f,.1f);
            refresh(gpu,state,volume,finish);
            int[] updated=results(gpu,volume.probeCount());
            state.disableCurrentFrame();
            refresh(gpu,state,volume,finish);
            int[] fresh=results(gpu,volume.probeCount());
            int compared=0,changed=0;
            for(int probe=0;probe<volume.probeCount();probe++)for(int channel=3;channel<28;channel++) {
                int at=probe*28+channel;
                if(updated[at]!=fresh[at])throw new AssertionError("Refresh differs from fresh native output probe="+probe+" channel="+channel);
                if(updated[at]!=migrated[at])changed++;
                compared++;
            }
            if(changed==0)throw new AssertionError("Fixture did not change incident sky lighting");
            System.out.println("Hardware irradiance migration GPU PASS: "+preserved+" completed probes retained exactly, "
                +compared+" fresh SH/AO words equal after full bounded refresh, "+changed+" changed words");
        } finally {gpu.close();}
    }

    private static void refresh(VulkanRayTracingGpuProbe gpu,VulkanHardwareIrradianceState state,
                                VulkanHardwareIrradianceVolume volume,Method finish) throws Exception {
        int budget=VulkanHardwareIrradianceVolume.budgetFor(0),count=volume.probeCount();
        for(int work=0;work<count;work+=budget) {
            state.prepareFrame(volume,0,1,true);
            ByteBuffer bytes=data(gpu);int cursor=bytes.getInt(8),pending=bytes.getInt(12);
            bytes.putInt(8,cursor).putInt(12,pending);
            gpu.dispatch(0,null,null,0,0,false,false,VulkanHardwareIrradianceShader.workgroupCount(pending));
            finish.invoke(state,0);
        }
    }
    private static void sky(VulkanRayTracingGpuProbe gpu,float r,float g,float b) {
        gpu.materialData.write(16,32,bytes -> bytes.order(ByteOrder.nativeOrder())
            .putFloat(r).putFloat(g).putFloat(b).putFloat(1)
            .putFloat(r).putFloat(g).putFloat(b).putFloat(0));
    }
    private static ByteBuffer data(VulkanRayTracingGpuProbe gpu) {
        return MemoryUtil.memByteBuffer(gpu.irradianceData.mappedAddress(),VulkanHardwareIrradianceVolume.MAX_STORAGE_BYTES)
            .order(ByteOrder.nativeOrder());
    }
    private static int[] results(VulkanRayTracingGpuProbe gpu,int count) {
        int[] results=new int[count*28];
        data(gpu).asIntBuffer().get(VulkanHardwareIrradianceVolume.RECORD_OFFSET_WORDS,results);
        return results;
    }
    private static Object get(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void set(Object object,String name,Object value) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
}
