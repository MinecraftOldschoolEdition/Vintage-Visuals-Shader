package net.minecraft.src.graphics.backend.vulkan;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.src.block.Block;
import net.minecraft.src.config.GameSettings;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.src.entity.EntityPig;
import net.minecraft.src.graphics.api.GraphicsBackendId;
import net.minecraft.src.graphics.api.GraphicsStaticMeshUsage;
import net.minecraft.src.graphics.api.RenderDevice;
import net.minecraft.src.graphics.api.RenderMeshMode;
import net.minecraft.src.graphics.runtime.GraphicsRuntime;
import net.minecraft.src.render.core.TessellatorMeshData;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.src.world.core.LightweightTestWorld;
import net.minecraft.src.world.core.VisualLightmap;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.PixelFormat;

/** Native Vulkan upload/draw/readback with disposable in-memory chunks and no user settings. */
public final class VulkanColoredLightNativeProbe {
    private static final int SIZE = 256;
    private static final float CAMERA_X = 4;
    private static final float CAMERA_Y = 69;
    private static final float CAMERA_Z = 11;
    private static final String GI_PROPERTY = "minecraft.vulkan.coloredGlobalIllumination.enabled";
    private static ProbeMinecraft client;
    private static ProbeWorld world;
    private static int mesh;
    private static int texture;

    private VulkanColoredLightNativeProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Files.createTempDirectory("colored-light-vulkan-") : Path.of(args[0]);
        Files.createDirectories(output);
        // Pack-local lighting now defaults to OFF; this fixture explicitly selects raster GI.
        System.setProperty("minecraft.vulkan.lighting.mode", "rasterized");
        System.setProperty("minecraft.vulkan.dynamicShadows.enabled", "false");
        System.setProperty("minecraft.vulkan.emissiveShadowCasting.enabled", "false");
        System.setProperty("minecraft.vulkan.dlss45.preProvisionExtensions", "false");
        System.setProperty("minecraft.vulkan.dlss45.enabled", "false");
        System.setProperty("minecraft.vulkan.fsr31.enabled", "false");
        installContext();
        setEnabled(false);
        try {
            GraphicsRuntime.initialize(GraphicsBackendId.VULKAN);
            Display.setTitle("Disposable native colored illumination probe");
            Display.setDisplayMode(new DisplayMode(SIZE, SIZE));
            Display.create(new PixelFormat().withDepthBits(24).withStencilBits(8));
            if (GraphicsRuntime.getActiveBackendId() != GraphicsBackendId.VULKAN)
                throw new AssertionError("Vulkan unavailable: " + GraphicsRuntime.getFallbackReason());
            RenderDevice.setMeshMode(RenderMeshMode.VBO);
            texture = createWhiteTexture();
            mesh = createReceiverMesh();
            for (int frame = 0; frame < 12; frame++) render();
            byte[] disabled = render();
            int visible = 0;
            for (int i = 0; i < disabled.length; i += 3)
                if ((disabled[i] & 255) > 40 && Math.abs((disabled[i] & 255) - (disabled[i + 2] & 255)) <= 1) visible++;
            if (visible < 1000) throw new AssertionError("fixture lacks visible gray receivers: " + visible);

            setEnabled(true);
            for (int frame = 0; frame < 4; frame++) render();
            byte[] colored = render();
            int warm = 0;
            for (int i = 0; i < colored.length; i += 3) {
                int red = colored[i] & 255;
                int green = colored[i + 1] & 255;
                int blue = colored[i + 2] & 255;
                if (red > (disabled[i] & 255) + 3 && red > green + 3 && green > blue + 2) warm++;
            }
            if (warm < 100) throw new AssertionError("lava produced no native colored light: warm pixels=" + warm);
            save(output.resolve("lava-disabled.png"), disabled);
            save(output.resolve("lava-colored.png"), colored);
            verifyStable(colored, "enabled steady frames");

            setEnabled(false);
            verifyStable(disabled, "disabled frame-buffer cycle");
            setEnabled(true);
            verifyStable(colored, "reenabled frame-buffer cycle");
            setSource(0);
            verifyStable(disabled, "removed lava source");
            setSource(Block.lavaStill.blockID);
            verifyStable(colored, "restored lava source");
            System.out.println("Native Vulkan colored GI PASS: gray receiver pixels=" + visible
                + ", lava-colored pixels=" + warm
                + ", 8 frames each for steady/on/off/source-remove/source-restore; output=" + output);
        } finally {
            if (mesh != 0) RenderDevice.deleteStaticMesh(mesh);
            if (texture != 0) RenderDevice.glDeleteTextures(texture);
            Display.destroy();
        }
    }

    private static void verifyStable(byte[] expected, String label) throws Exception {
        for (int frame = 0; frame < 8; frame++) {
            byte[] actual = render();
            int changed = 0;
            for (int i = 0; i < actual.length; i++)
                if (Math.abs((expected[i] & 255) - (actual[i] & 255)) > 1) changed++;
            if (changed != 0) throw new AssertionError(label + ": frame=" + frame + " changed channels=" + changed);
        }
    }

    private static void setEnabled(boolean enabled) {
        client.gameSettings.coloredGlobalIllumination = enabled;
        System.setProperty(GI_PROPERTY, Boolean.toString(enabled));
    }

    private static void setSource(int blockId) {
        ChunkAccess chunk = world.prepareTestChunk(0, 0);
        chunk.blocks[65] = (byte)blockId;
        chunk.setBlockMetadata(0, 65, 0, (chunk.getBlockMetadata(0, 65, 0) + 1) & 15);
        chunk.rebuildNonEmptySectionSummary();
    }

    private static byte[] render() throws Exception {
        RenderDevice.setRenderPhase(RenderDevice.RENDER_PHASE_WORLD_SCENE);
        RenderDevice.setRenderPurpose(RenderDevice.RENDER_PURPOSE_MAIN_WORLD);
        RenderDevice.glViewport(0, 0, SIZE, SIZE);
        RenderDevice.glMatrixMode(RenderDevice.GL_PROJECTION);
        RenderDevice.glLoadIdentity();
        RenderDevice.gluPerspective(70, 1, 0.1f, 128);
        RenderDevice.glMatrixMode(RenderDevice.GL_MODELVIEW);
        RenderDevice.glLoadIdentity();
        RenderDevice.glRotatef(25, 1, 0, 0);
        RenderDevice.glEnable(RenderDevice.GL_TEXTURE_2D);
        RenderDevice.glDisable(RenderDevice.GL_LIGHTING);
        RenderDevice.glDisable(RenderDevice.GL_FOG);
        RenderDevice.glDisable(RenderDevice.GL_CULL_FACE);
        RenderDevice.glDisable(RenderDevice.GL_BLEND);
        RenderDevice.glDisable(RenderDevice.GL_ALPHA_TEST);
        RenderDevice.glEnable(RenderDevice.GL_DEPTH_TEST);
        RenderDevice.glDepthFunc(RenderDevice.GL_LEQUAL);
        RenderDevice.glDepthMask(true);
        RenderDevice.glColor4f(1, 1, 1, 1);
        RenderDevice.glClearColor(0.02f, 0.04f, 0.08f, 1);
        RenderDevice.glClear(RenderDevice.GL_COLOR_BUFFER_BIT | RenderDevice.GL_DEPTH_BUFFER_BIT);
        RenderDevice.uploadDeferredStaticMeshes(100, 100000000L);
        RenderDevice.glBindTexture(RenderDevice.GL_TEXTURE_2D, texture);
        var state = RenderDevice.captureTerrainDrawState(null, CAMERA_X, CAMERA_Y, CAMERA_Z);
        IntBuffer meshes = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        meshes.put(mesh).flip();
        RenderDevice.drawTerrainStaticMeshes(state, meshes, null, null,
            -CAMERA_X, -CAMERA_Y, -CAMERA_Z, 0, 0, 0);
        Display.swapBuffers();
        if (GraphicsRuntime.hasTerminalDeviceFailure()) throw new AssertionError("Vulkan device failed");
        ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 3);
        RenderDevice.glPixelStorei(RenderDevice.GL_PACK_ALIGNMENT, 1);
        RenderDevice.glReadPixels(0, 0, SIZE, SIZE, RenderDevice.GL_RGB, RenderDevice.GL_UNSIGNED_BYTE, pixels);
        byte[] bytes = new byte[pixels.capacity()];
        pixels.position(0);
        pixels.get(bytes);
        return bytes;
    }

    private static int createReceiverMesh() {
        ArrayList<Integer> words = new ArrayList<>();
        for (int x = -8; x < 8; x++) for (int z = -8; z < 8; z++) {
            float[][] positions = {{x, 64, z}, {x + 1, 64, z}, {x + 1, 64, z + 1}, {x, 64, z + 1}};
            float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int vertex : new int[]{0, 1, 2, 2, 3, 0}) {
                for (float position : positions[vertex]) words.add(Float.floatToRawIntBits(position));
                words.add(Float.floatToRawIntBits(uv[vertex][0]));
                words.add(Float.floatToRawIntBits(uv[vertex][1]));
                words.add(0xFF404040);
                words.add(0);
                words.add(VisualLightmap.packWithInitialBrightness(0, 15, 1));
            }
        }
        int[] packed = words.stream().mapToInt(Integer::intValue).toArray();
        var data = new TessellatorMeshData(packed, packed.length, packed.length / 8,
            RenderDevice.GL_TRIANGLES, true, true, false);
        try {
            int mesh = RenderDevice.createStaticMesh(data, false, 0, 0, 0, false, GraphicsStaticMeshUsage.TERRAIN_SOLID, true);
            if (mesh <= 0) throw new AssertionError("receiver mesh upload rejected");
            return mesh;
        } finally {
            data.release();
        }
    }

    private static int createWhiteTexture() {
        int texture = RenderDevice.glGenTextures();
        RenderDevice.glBindTexture(RenderDevice.GL_TEXTURE_2D, texture);
        RenderDevice.glTexParameteri(RenderDevice.GL_TEXTURE_2D, RenderDevice.GL_TEXTURE_MIN_FILTER, RenderDevice.GL_NEAREST);
        RenderDevice.glTexParameteri(RenderDevice.GL_TEXTURE_2D, RenderDevice.GL_TEXTURE_MAG_FILTER, RenderDevice.GL_NEAREST);
        ByteBuffer white = ByteBuffer.allocateDirect(4);
        white.putInt(-1).flip();
        RenderDevice.glTexImage2D(RenderDevice.GL_TEXTURE_2D, 0, RenderDevice.GL_RGBA, 1, 1, 0,
            RenderDevice.GL_RGBA, RenderDevice.GL_UNSIGNED_BYTE, white);
        return texture;
    }

    private static void installContext() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe)field.get(null);
        client = (ProbeMinecraft)unsafe.allocateInstance(ProbeMinecraft.class);
        client.gameSettings = (GameSettings)unsafe.allocateInstance(GameSettings.class);
        client.gameSettings.accessibilityColorProfile = "normal";
        client.gameSettings.rendererBackend = "VULKAN";
        client.gameSettings.rendererVboMode = "VBO";
        world = new ProbeWorld();
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) {
            ChunkAccess chunk = world.prepareTestChunk(x, z);
            chunk.hasChunkData = true;
        }
        for (int z = -8; z < 8; z++) for (int x = -8; x < 8; x++) {
            ChunkAccess chunk = world.prepareTestChunk(x >> 4, z >> 4);
            chunk.blocks[(x & 15) << 11 | (z & 15) << 7 | 63] = (byte)Block.stone.blockID;
            chunk.rebuildNonEmptySectionSummary();
        }
        setSource(Block.lavaStill.blockID);
        client.theWorld = world;
        LivingEntity camera = (LivingEntity)unsafe.allocateInstance(EntityPig.class);
        camera.prevPosX = camera.posX = CAMERA_X;
        camera.prevPosY = camera.posY = CAMERA_Y;
        camera.prevPosZ = camera.posZ = CAMERA_Z;
        camera.worldObj = world;
        client.renderViewEntity = camera;
        var instance = Minecraft.class.getDeclaredField("theMinecraft");
        instance.setAccessible(true);
        instance.set(null, client);
    }

    private static final class ProbeMinecraft extends Minecraft {
        private ProbeMinecraft() { super(null, null, SIZE, SIZE, false); }
        @Override public void displayUnexpectedThrowable(net.minecraft.src.util.UnexpectedThrowable failure) {
            throw new AssertionError(failure);
        }
        @Override public int getSceneRenderTargetWidth() { return SIZE; }
        @Override public int getSceneRenderTargetHeight() { return SIZE; }
        @Override public float getRenderPartialTicks() { return 0.5f; }
    }

    private static final class ProbeWorld extends LightweightTestWorld {
        private ProbeWorld() { super("native-colored-illumination-probe"); }
        @Override public float getCelestialAngle(float partial) { return 0.5f; }
        @Override public float getRainStrength(float partial) { return 0; }
        @Override public int getPackedWeatherLightComponents(int x, int y, int z) {
            return VisualLightmap.pack(0, 15);
        }
    }

    private static void save(Path path, byte[] pixels) throws Exception {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int i = ((SIZE - 1 - y) * SIZE + x) * 3;
            image.setRGB(x, y, (pixels[i] & 255) << 16 | (pixels[i + 1] & 255) << 8 | (pixels[i + 2] & 255));
        }
        ImageIO.write(image, "png", path.toFile());
    }
}
