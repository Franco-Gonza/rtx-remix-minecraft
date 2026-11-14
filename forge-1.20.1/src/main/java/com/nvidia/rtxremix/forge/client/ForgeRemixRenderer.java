/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.forge.gl.GlInterop;
import com.nvidia.rtxremix.forge.gl.TextureOverlay;
import com.nvidia.rtxremix.forge.terrain.capture.SectionBlockData;
import com.nvidia.rtxremix.forge.terrain.capture.SectionMeshData;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Render-thread bridge between Minecraft 1.20.1 Forge and the RTX Remix native runtime.
 * Manages path-tracing initialization, window swapchain presentation, and TextureOverlay composition.
 */
public final class ForgeRemixRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/Renderer");
    private static final ForgeRemixRenderer INSTANCE = new ForgeRemixRenderer();

    private final Minecraft client = Minecraft.getInstance();
    private final com.nvidia.rtxremix.forge.sky.SkyRenderer skyRenderer = new com.nvidia.rtxremix.forge.sky.SkyRenderer();
    private final com.nvidia.rtxremix.forge.light.HeldLightManager heldLightManager = new com.nvidia.rtxremix.forge.light.HeldLightManager();
    private final com.nvidia.rtxremix.forge.logic.ConfLayerDriver confLayerDriver = new com.nvidia.rtxremix.forge.logic.ConfLayerDriver();
    private final java.util.List<com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.QueuedCapture> drainedCaptures = new java.util.ArrayList<>();
    private RemixAPI api;
    private GlInterop interop;
    private TextureOverlay overlay;
    private com.nvidia.rtxremix.forge.entity.RemixEntitySubmitter entitySubmitter;
    private com.nvidia.rtxremix.forge.entity.RemixEntityPass entityPass;
    private com.nvidia.rtxremix.forge.entity.RemixBlockEntityPass blockEntityPass;
    private com.nvidia.rtxremix.forge.particle.RemixParticleRenderer particleRenderer;
    private long terrainAtlasTexture = 0;
    private long defaultTerrainMaterial = 0;
    private long solidTerrainMaterial = 0;
    private long cutoutTerrainMaterial = 0;
    private long waterMaterial = 0;
    private long waterCameraMediumMaterial = 0;
    private long lastCameraMediumHandle = 0;
    private long translucentTerrainMaterial = 0;
    private long waterNormalTexture = 0;
    private long waterWhiteTexture = 0;
    private float lastPartialTicks = 0.0f;

    private boolean initialized = false;
    private boolean active = false;
    private boolean submitFrameCalled = false;
    private long frameIndex = 0;
    private static volatile boolean renderingPostWeather = false;
    private final java.util.concurrent.atomic.AtomicBoolean destroyed = new java.util.concurrent.atomic.AtomicBoolean(false);

    private ForgeRemixRenderer() {}

    public static ForgeRemixRenderer getInstance() {
        return INSTANCE;
    }

    public static boolean isRtxEnabled() {
        return INSTANCE.active && INSTANCE.initialized && INSTANCE.api != null;
    }

    public static boolean isRenderingPostWeather() {
        return renderingPostWeather;
    }

    public static void setRenderingPostWeather(boolean value) {
        renderingPostWeather = value;
    }

    public com.nvidia.rtxremix.forge.sky.SkyRenderer getSkyRenderer() {
        return skyRenderer;
    }

    public com.nvidia.rtxremix.forge.light.HeldLightManager getHeldLightManager() {
        return heldLightManager;
    }

    private boolean menuOpen = false;

    public synchronized boolean initializeIfSupported() {
        if (initialized && api != null) {
            return true;
        }
        destroyed.set(false);

        long windowHandle = client.getWindow().getWindow();
        if (windowHandle == 0) {
            LOGGER.warn("GLFW window not yet ready for RTX Remix initialization.");
            return false;
        }

        long hwnd = GLFWNativeWin32.glfwGetWin32Window(windowHandle);
        LOGGER.info("Initializing RTX Remix API against Win32 HWND: 0x{}", Long.toHexString(hwnd));

        try {
            com.nvidia.rtxremix.forge.runtime.ForgeNativeExtractor.ensureExtracted();
            java.nio.file.Path d3d9 = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("remix-sdk").resolve("d3d9.dll");
            String dllPath = d3d9.toAbsolutePath().toString();

            if (java.nio.file.Files.exists(d3d9)) {
                RemixAPI.redactCommandLineOptions(dllPath, java.util.List.of("--username", "--accessToken", "--uuid", "--xuid", "--clientId"));
                api = RemixAPI.load(dllPath);
            } else {
                LOGGER.warn("d3d9.dll not found at {}; runtime might be mocked or missing.", dllPath);
                return false;
            }

            StartupInfo startup = new StartupInfo.Builder()
                    .hwnd(hwnd)
                    .forceNoVkSwapchain(false)
                    .interopImage(true)
                    .useRemixOwnedChildWindow(true)
                    .build();

            api.startup(startup);

            try {
                InteropInfo interopInfo = api.dxvkGetInteropInfo();
                LOGGER.info(
                        "GL interop: got Remix info: dxvkDoneSemaphore_Handle=0x{}, clientDoneSemaphore_Handle=0x{}, imageMemory_Handle=0x{}, w={}, h={}",
                        Long.toHexString(interopInfo.dxvkDoneSemaphore_Handle),
                        Long.toHexString(interopInfo.clientDoneSemaphore_Handle),
                        Long.toHexString(interopInfo.imageMemory_Handle),
                        interopInfo.width, interopInfo.height);
                interop = new GlInterop(interopInfo);
                LOGGER.info("GL interop initialized successfully.");
            } catch (Exception e) {
                LOGGER.error("Failed to initialize GL interop: {}", e.getMessage(), e);
                interop = null;
            }

            overlay = new TextureOverlay();
            try {
                MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                        .albedoConstant(1.0f, 1.0f, 1.0f)
                        .roughnessConstant(0.8f)
                        .metallicConstant(0.0f)
                        .useDrawCallAlphaState(false)
                        .build();
                MaterialInfo matInfo = MaterialInfo.builder()
                        .opaqueExt(opaque)
                        .build();
                defaultTerrainMaterial = api.createMaterial(matInfo);
            } catch (Exception e) {
                LOGGER.warn("Failed creating default terrain material: {}", e.getMessage());
            }
            entitySubmitter = new com.nvidia.rtxremix.forge.entity.RemixEntitySubmitter(api);
            entityPass = new com.nvidia.rtxremix.forge.entity.RemixEntityPass();
            blockEntityPass = new com.nvidia.rtxremix.forge.entity.RemixBlockEntityPass();
            particleRenderer = new com.nvidia.rtxremix.forge.particle.RemixParticleRenderer(client);
            particleRenderer.initialize(api);
            confLayerDriver.acquireAll(api, net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
            active = true;
            initialized = true;
            LOGGER.info("NVIDIA RTX Remix initialized successfully on Forge 1.20.1.");
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize RTX Remix runtime: {}", e.getMessage(), e);
            api = null;
            active = false;
            initialized = false;
            return false;
        }
    }

    private final byte[] resolvedVisibilityBuffer = new byte[8];

    public void followRemixMenuVisibility() {
        if (!isRtxEnabled()) return;
        try {
            int size = api.getConfigVariableResolved("rtx.showUI", resolvedVisibilityBuffer);
            if (size > 0) {
                boolean shown = resolvedVisibilityBuffer[0] != '0' && resolvedVisibilityBuffer[0] != 0;
                if (shown != menuOpen) {
                    menuOpen = shown;
                    LOGGER.info("Remix menu visibility followed runtime state: {}", menuOpen ? "OPEN" : "CLOSED");
                    if (!shown && client != null && client.screen == null && client.mouseHandler != null) {
                        client.mouseHandler.grabMouse();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public void toggleMenu() {
        if (!isRtxEnabled()) {
            LOGGER.warn("Cannot toggle RTX Remix menu: renderer not initialized or not active.");
            return;
        }
        menuOpen = !menuOpen;
        try {
            api.setConfigVariable("rtx.showUI", menuOpen ? "2" : "0");
            LOGGER.info("RTX Remix menu toggled: {}", menuOpen ? "OPEN (Developer)" : "CLOSED");
            if (menuOpen && client != null && client.mouseHandler != null) {
                client.mouseHandler.releaseMouse();
            } else if (!menuOpen && client != null && client.screen == null && client.mouseHandler != null) {
                client.mouseHandler.grabMouse();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to set rtx.showUI config variable", e);
        }
    }

    public boolean isMenuOpen() {
        return menuOpen;
    }

    public boolean isInitialized() {
        return api != null;
    }

    public boolean hasInterop() {
        return interop != null;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        if (!active) {
            clearLevelState();
        } else {
            updateTerrainMaterialIfReady();
        }
        if (client != null && client.levelRenderer != null) {
            client.levelRenderer.allChanged();
            LOGGER.info("RTX Remix active state toggled to {}. Rebuilt all chunks.", active);
        }
    }

    private final java.util.Map<SectionPos, java.util.List<Long>> sectionInstances = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<SectionPos, java.util.List<Long>> sectionMeshes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<SectionPos, java.util.List<Long>> sectionLights = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean hasTerrainMeshes() {
        return !sectionInstances.isEmpty();
    }

    public void beginFrame(Camera camera, float partialTicks) {
        if (!isInitialized() || !active) return;

        followRemixMenuVisibility();

        this.lastPartialTicks = partialTicks;
        submitFrameCalled = false;
        updateTerrainMaterialIfReady();

        frameIndex++;
        Vec3 pos = camera.getPosition();
        updateCamera(camera, partialTicks);

        boolean isUnderwater = false;
        if (client.level != null) {
            skyRenderer.render(api, client.level, pos, partialTicks);
            heldLightManager.update(api, client.level, camera, partialTicks);
            isUnderwater = camera.getFluidInCamera() == net.minecraft.world.level.material.FogType.WATER;
            confLayerDriver.update(client.level, pos, isUnderwater, 1.0f / 60.0f);
        }

        long targetMedium = (isUnderwater && waterCameraMediumMaterial != 0) ? waterCameraMediumMaterial : 0;
        if (targetMedium != lastCameraMediumHandle) {
            try {
                api.setCameraMediumMaterial(targetMedium);
                lastCameraMediumHandle = targetMedium;
                LOGGER.info("Camera medium updated: {}", isUnderwater ? "water" : "clear");
            } catch (Exception e) {
                LOGGER.warn("Failed updating camera medium material: {}", e.getMessage());
            }
        }

        if (entityPass != null && entitySubmitter != null) {
            entityPass.beginFrame(entitySubmitter);
        }
        if (blockEntityPass != null) {
            blockEntityPass.beginFrame();
        }
    }

    public void updateTerrainMaterialIfReady() {
        if (terrainAtlasTexture != 0 || api == null || client == null) return;
        try {
            if (client.getModelManager() == null) return;
            net.minecraft.client.renderer.texture.TextureAtlas atlas =
                    client.getModelManager().getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            if (atlas == null) return;

            int texId = atlas.getId();
            if (texId <= 0) return;

            int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texId);
            try {
                int width = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
                int height = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);

            if (width > 0 && height > 0) {
                java.nio.ByteBuffer buffer = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4);
                try {
                    byte[] stitched = stitchAtlasRGBA(atlas, width, height);
                    buffer.put(stitched);
                    buffer.flip();
                    long texHash = 0x424C_4F43_4B53_0001L;
                    TextureFromMemoryInfo texInfo = TextureFromMemoryInfo.builder()
                            .hash(texHash)
                            .size(width, height)
                            .format(TextureFormat.RGBA8_UNORM)
                            .pixelData(buffer)
                            .build();
                    terrainAtlasTexture = api.createTextureFromMemory(texInfo);
                    LOGGER.info("Stitched and uploaded Minecraft terrain atlas texture ({}x{}) to RTX Remix: handle=0x{}",
                            width, height, Long.toHexString(terrainAtlasTexture));

                    // 1. Solid terrain material (opaque block atlas)
                    MaterialInfoOpaqueEXT solidOpaque = MaterialInfoOpaqueEXT.builder()
                            .albedoConstant(1.0f, 1.0f, 1.0f)
                            .roughnessConstant(0.8f)
                            .metallicConstant(0.0f)
                            .useDrawCallAlphaState(false)
                            .build();
                    MaterialInfo solidInfo = MaterialInfo.builder()
                            .hash(0x4D41_545F_534F_4C49L) // "MAT_SOLI"
                            .albedoTexture(terrainAtlasTexture)
                            .filterMode(0)
                            .opaqueExt(solidOpaque)
                            .build();
                    solidTerrainMaterial = api.createMaterial(solidInfo);
                    defaultTerrainMaterial = solidTerrainMaterial;

                    // 2. Cutout terrain material (foliage, leaves, tall grass, flowers)
                    MaterialInfoOpaqueEXT cutoutOpaque = MaterialInfoOpaqueEXT.builder()
                            .albedoConstant(1.0f, 1.0f, 1.0f)
                            .roughnessConstant(0.8f)
                            .metallicConstant(0.0f)
                            .alphaTestType(4) // GREATER_OR_EQUAL
                            .alphaReferenceValue(128)
                            .useDrawCallAlphaState(false)
                            .build();
                    MaterialInfo cutoutInfo = MaterialInfo.builder()
                            .hash(0x4D41_545F_4355_544FL) // "MAT_CUTO"
                            .albedoTexture(terrainAtlasTexture)
                            .filterMode(0)
                            .opaqueExt(cutoutOpaque)
                            .build();
                    cutoutTerrainMaterial = api.createMaterial(cutoutInfo);

                    // 3. Water material (refractive, animated normal flipbook, no diffuse layer)
                    java.nio.ByteBuffer whiteBuf = org.lwjgl.system.MemoryUtil.memAlloc(4);
                    try {
                        whiteBuf.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
                        whiteBuf.flip();
                        TextureFromMemoryInfo whiteInfo = TextureFromMemoryInfo.builder()
                                .hash(0x5748_4954_4500_0001L) // "WHITE"
                                .size(1, 1)
                                .format(TextureFormat.RGBA8_UNORM)
                                .pixelData(whiteBuf)
                                .build();
                        waterWhiteTexture = api.createTextureFromMemory(whiteInfo);
                    } finally {
                        org.lwjgl.system.MemoryUtil.memFree(whiteBuf);
                    }

                    try {
                        java.nio.file.Path normalDds = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                                .resolve("textures").resolve("water_normals_flipbook.dds");
                        if (java.nio.file.Files.exists(normalDds)) {
                            waterNormalTexture = api.createTextureFromFile(normalDds.toAbsolutePath().toString());
                            LOGGER.info("Loaded water normals flipbook texture from {}: handle=0x{}",
                                    normalDds, Long.toHexString(waterNormalTexture));
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed loading water normals flipbook: {}", e.getMessage());
                        waterNormalTexture = 0;
                    }

                    MaterialInfoTranslucentEXT waterExt = MaterialInfoTranslucentEXT.builder()
                            .refractiveIndex(1.33f)
                            .transmittanceColor(0.60f, 0.90f, 0.89f)
                            .transmittanceMeasurementDistance(1.0f)
                            .useDiffuseLayer(false)
                            .build();
                    MaterialInfo.Builder waterBuilder = MaterialInfo.builder()
                            .hash(0x5741_5445_525F_4D41L) // "WATER_MA"
                            .albedoTexture(waterWhiteTexture)
                            .filterMode(1)
                            .translucentExt(waterExt);
                    if (waterNormalTexture != 0) {
                        waterBuilder.normalTexture(waterNormalTexture)
                                .spriteSheetRow(10)
                                .spriteSheetCol(12)
                                .spriteSheetFps(20);
                    }
                    waterMaterial = api.createMaterial(waterBuilder.build());

                    // 3b. Underwater camera medium material (absorption & volume fog inside water)
                    MaterialInfoTranslucentEXT mediumExt = MaterialInfoTranslucentEXT.builder()
                            .refractiveIndex(1.33f)
                            .transmittanceColor(0.98f, 0.98f, 0.98f)
                            .transmittanceMeasurementDistance(1.0f)
                            .useDiffuseLayer(false)
                            .build();
                    MaterialInfo mediumInfo = MaterialInfo.builder()
                            .hash(0x5741_5445_525F_4D45L) // "WATER_ME"
                            .albedoTexture(waterWhiteTexture)
                            .filterMode(1)
                            .translucentExt(mediumExt)
                            .build();
                    waterCameraMediumMaterial = api.createMaterial(mediumInfo);

                    // 4. Translucent terrain material (stained glass, ice)
                    MaterialInfoTranslucentEXT transExt = MaterialInfoTranslucentEXT.builder()
                            .refractiveIndex(1.5f)
                            .transmittanceColor(0.97f, 0.97f, 0.97f)
                            .transmittanceMeasurementDistance(0.35f)
                            .useDiffuseLayer(true)
                            .build();
                    MaterialInfo transInfo = MaterialInfo.builder()
                            .hash(0x4D41_545F_5452_414EL) // "MAT_TRAN"
                            .albedoTexture(terrainAtlasTexture)
                            .filterMode(0)
                            .translucentExt(transExt)
                            .build();
                    translucentTerrainMaterial = api.createMaterial(transInfo);

                    LOGGER.info("Created specialized terrain materials: solid=0x{}, cutout=0x{}, water=0x{}, trans=0x{}",
                            Long.toHexString(solidTerrainMaterial),
                            Long.toHexString(cutoutTerrainMaterial),
                            Long.toHexString(waterMaterial),
                            Long.toHexString(translucentTerrainMaterial));

                    // Trigger level chunk reload so all chunks use the definitive materials
                    if (client.levelRenderer != null) {
                        client.levelRenderer.allChanged();
                        LOGGER.info("Triggered levelRenderer.allChanged() to rebuild chunk meshes with definitive materials.");
                    }
                } finally {
                    org.lwjgl.system.MemoryUtil.memFree(buffer);
                }
            }
        } finally {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex);
        }
    } catch (Exception e) {
            LOGGER.error("Failed to capture and upload terrain atlas: {}", e.getMessage(), e);
        }
    }

    private static byte[] stitchAtlasRGBA(net.minecraft.client.renderer.texture.TextureAtlas atlas, int width, int height) {
        byte[] buf = new byte[width * height * 4];
        int stitchedCount = 0;
        int nonZeroPixels = 0;
        try {
            java.util.Set<net.minecraft.resources.ResourceLocation> locations = atlas.getTextureLocations();
            if (locations != null && !locations.isEmpty()) {
                for (net.minecraft.resources.ResourceLocation loc : locations) {
                    net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = atlas.getSprite(loc);
                    if (sprite == null) continue;
                    net.minecraft.client.renderer.texture.SpriteContents contents = sprite.contents();
                    if (contents == null) continue;
                    com.mojang.blaze3d.platform.NativeImage img = contents.getOriginalImage();
                    if (img == null) continue;

                    int sx = sprite.getX();
                    int sy = sprite.getY();
                    int sw = contents.width();
                    int sh = contents.height();

                    for (int y = 0; y < sh && (sy + y) < height; y++) {
                        for (int x = 0; x < sw && (sx + x) < width; x++) {
                            int abgr = img.getPixelRGBA(x, y);
                            int idx = ((sy + y) * width + (sx + x)) * 4;
                            byte r = (byte) (abgr & 0xFF);
                            byte g = (byte) ((abgr >> 8) & 0xFF);
                            byte b = (byte) ((abgr >> 16) & 0xFF);
                            byte a = (byte) ((abgr >> 24) & 0xFF);
                            buf[idx]     = r;
                            buf[idx + 1] = g;
                            buf[idx + 2] = b;
                            buf[idx + 3] = a;
                            if (r != 0 || g != 0 || b != 0 || a != 0) {
                                nonZeroPixels++;
                            }
                        }
                    }
                    stitchedCount++;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("CPU atlas stitching encountered error: {}", t.getMessage());
        }

        LOGGER.info("CPU atlas stitch result: {} sprites stitched, {} non-zero pixels out of {}",
                stitchedCount, nonZeroPixels, width * height);

        // Fallback to glGetTexImage if CPU stitching did not populate the atlas
        if (stitchedCount == 0 || nonZeroPixels < 1000) {
            LOGGER.warn("CPU atlas stitching produced insufficient pixels ({}). Falling back to glGetTexImage...", nonZeroPixels);
            java.nio.ByteBuffer glBuf = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4);
            try {
                org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                        org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, glBuf);
                glBuf.get(buf);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(glBuf);
            }
        }
        return buf;
    }

    private void updateCamera(Camera camera, float tickDelta) {
        try {
            Vec3 pos = camera.getPosition();
            float pitch = camera.getXRot();
            float yaw = camera.getYRot();

            float yawRad = (float) Math.toRadians(-yaw);
            float pitchRad = (float) Math.toRadians(pitch);

            float forwardX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
            float forwardY = (float) (-Math.sin(pitchRad));
            float forwardZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));

            float rightX = (float) (-Math.cos(yawRad));
            float rightY = 0.0f;
            float rightZ = (float) (Math.sin(yawRad));

            float upX = rightY * forwardZ - rightZ * forwardY;
            float upY = rightZ * forwardX - rightX * forwardZ;
            float upZ = rightX * forwardY - rightY * forwardX;

            float worldFov = client.options.fov().get().floatValue();
            int width = Math.max(1, client.getWindow().getWidth());
            int height = Math.max(1, client.getWindow().getHeight());
            float aspect = (float) width / (float) height;

            CameraInfoParameterizedEXT worldCameraParams = CameraInfoParameterizedEXT.builder()
                .position((float) pos.x, (float) pos.y, (float) pos.z)
                .forward(forwardX, forwardY, forwardZ)
                .up(upX, upY, upZ)
                .right(rightX, rightY, rightZ)
                .fovYInDegrees(worldFov)
                .aspect(aspect)
                .nearPlane(0.05f)
                .farPlane(1000.0f)
                .build();

            CameraInfo worldCameraInfo = CameraInfo.builder()
                .type(CameraType.WORLD)
                .parameterizedExt(worldCameraParams)
                .build();

            CameraInfo viewModelCameraInfo = CameraInfo.builder()
                .type(CameraType.VIEW_MODEL)
                .parameterizedExt(worldCameraParams)
                .build();

            api.setupCamera(worldCameraInfo);
            api.setupCamera(viewModelCameraInfo);
        } catch (Exception e) {
            LOGGER.error("Error updating camera in Remix: {}", e.getMessage(), e);
        }
    }

    public boolean renderEntityInPlace(net.minecraft.world.entity.Entity entity, float tickDelta) {
        if (!isRtxEnabled() || entityPass == null || entitySubmitter == null) {
            return false;
        }
        if (client == null || client.getEntityRenderDispatcher() == null) {
            return false;
        }
        entityPass.captureAndSubmit(entity, client.getEntityRenderDispatcher(), tickDelta, entitySubmitter);
        return true;
    }

    public boolean renderBlockEntityInPlace(net.minecraft.world.level.block.entity.BlockEntity be, float tickDelta) {
        if (!isRtxEnabled() || blockEntityPass == null || entitySubmitter == null) {
            return false;
        }
        if (client == null || client.getBlockEntityRenderDispatcher() == null) {
            return false;
        }
        return blockEntityPass.capture(be, client.getBlockEntityRenderDispatcher(), tickDelta, entitySubmitter);
    }

    public void submitScene() {
        if (!isInitialized() || !active) return;

        try {
            updateTerrainMaterialIfReady();

            // Drain captured block terrain data and submit to Remix
            drainedCaptures.clear();
            com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.drain(drainedCaptures);
            for (com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.QueuedCapture capture : drainedCaptures) {
                if (capture == null || capture.data() == null) continue;
                SectionBlockData blockData = capture.data();
                SectionMeshData meshData = blockData.meshData();
                if (meshData == null) continue;

                SectionPos pos = blockData.sectionPos();

                // Clean up previous instances and meshes for this section
                java.util.List<Long> oldInstances = sectionInstances.remove(pos);
                if (oldInstances != null) {
                    for (Long inst : oldInstances) {
                        if (inst != null && inst != 0) {
                            try { api.sceneInstanceRemove(inst); } catch (Exception ignored) {}
                        }
                    }
                }
                java.util.List<Long> oldMeshes = sectionMeshes.remove(pos);
                if (oldMeshes != null) {
                    for (Long m : oldMeshes) {
                        if (m != null && m != 0) {
                            try { api.destroyMesh(m); } catch (Exception ignored) {}
                        }
                    }
                }
                java.util.List<Long> oldLights = sectionLights.remove(pos);
                if (oldLights != null) {
                    for (Long l : oldLights) {
                        if (l != null && l != 0) {
                            try { api.destroyLight(l); } catch (Exception ignored) {}
                        }
                    }
                }

                try {
                    if (!meshData.isEmpty()) {
                        java.util.List<Long> newMeshes = new java.util.ArrayList<>();
                        java.util.List<Long> newInstances = new java.util.ArrayList<>();
                        Transform sectionTransform = new Transform();
                        sectionTransform.setTranslation(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());

                        for (SectionMeshData.Surface surface : meshData.surfaces()) {
                            int vCount = surface.vertexCount();
                            int iCount = surface.indexCount();
                            if (vCount <= 0 || iCount <= 0) continue;

                            java.nio.ByteBuffer vBuf = surface.vertexBuffer();
                            java.nio.ByteBuffer iBuf = com.nvidia.rtxremix.forge.util.CanonicalIndexBuffer.getBuffer(iCount);
                            try {
                                long matHandle;
                                int doubleSided = 1;
                                int categoryFlags = 0;
                                if ("water".equals(surface.bucketKey())) {
                                    matHandle = waterMaterial != 0 ? waterMaterial : defaultTerrainMaterial;
                                } else if (surface.category() == SectionMeshData.CAT_OVERLAY) {
                                    matHandle = cutoutTerrainMaterial != 0 ? cutoutTerrainMaterial : defaultTerrainMaterial;
                                    categoryFlags = InstanceCategoryBit.DECAL_STATIC;
                                } else if (surface.category() == SectionMeshData.CAT_CUTOUT) {
                                    matHandle = cutoutTerrainMaterial != 0 ? cutoutTerrainMaterial : defaultTerrainMaterial;
                                } else if (surface.category() == SectionMeshData.CAT_TRANSLUCENT) {
                                    matHandle = translucentTerrainMaterial != 0 ? translucentTerrainMaterial : defaultTerrainMaterial;
                                } else {
                                    matHandle = solidTerrainMaterial != 0 ? solidTerrainMaterial : defaultTerrainMaterial;
                                }

                                MeshInfoSurfaceTriangles surf = new MeshInfoSurfaceTriangles();
                                surf.vertexBuffer = vBuf;
                                surf.vertexCount = vCount;
                                surf.indexBuffer = iBuf;
                                surf.indexCount = iCount;
                                surf.material = matHandle;
                                surf.flags = com.nvidia.rtxremix.MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS; // Enable texture atlas bounds clamping

                                long hash = java.util.concurrent.ThreadLocalRandom.current().nextLong() | 1L;
                                MeshInfo mi = MeshInfo.builder()
                                        .hash(hash)
                                        .surfaces(surf)
                                        .build();

                                long mHandle = api.createMesh(mi);
                                if (mHandle != 0) {
                                    newMeshes.add(mHandle);
                                    long instHandle = api.sceneInstanceAdd(mHandle, sectionTransform.matrix, categoryFlags, doubleSided, 0);
                                    if (instHandle != 0) {
                                        newInstances.add(instHandle);
                                    }
                                }
                            } catch (Exception e) {
                                LOGGER.error("Failed creating terrain mesh for section {}", pos, e);
                            }
                        }
                        if (!newMeshes.isEmpty()) {
                            sectionMeshes.put(pos, newMeshes);
                        }
                        if (!newInstances.isEmpty()) {
                            sectionInstances.put(pos, newInstances);
                        }
                    }
                } finally {
                    meshData.release();
                }

                java.util.List<com.nvidia.rtxremix.forge.light.LightEntry> bLights = blockData.lights();
                if (bLights != null && !bLights.isEmpty()) {
                    java.util.List<Long> newLights = new java.util.ArrayList<>();
                    for (com.nvidia.rtxremix.forge.light.LightEntry entry : bLights) {
                        try {
                            float r = entry.hasColor() ? entry.colorR() : 1.0f;
                            float g = entry.hasColor() ? entry.colorG() : 0.85f;
                            float b = entry.hasColor() ? entry.colorB() : 0.6f;
                            float baseIntensity = entry.hasIntensity() ? entry.intensity() : (entry.lightLevel() * 12.0f);
                            baseIntensity *= entry.authoredIntensityMultiplier();

                            LightInfo.Builder lib = LightInfo.builder()
                                    .hash(java.util.concurrent.ThreadLocalRandom.current().nextLong() | 1L)
                                    .radiance(r * baseIntensity, g * baseIntensity, b * baseIntensity);

                            switch (entry.shape()) {
                                case SPHERE -> {
                                    LightInfoSphereEXT sphere = LightInfoSphereEXT.builder()
                                            .position(entry.x(), entry.y(), entry.z())
                                            .radius(entry.radius() > 0 ? entry.radius() : 0.05f)
                                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                                            .build();
                                    lib.sphereExt(sphere);
                                }
                                case CYLINDER -> {
                                    LightInfoCylinderEXT cyl = LightInfoCylinderEXT.builder()
                                            .position(entry.x(), entry.y(), entry.z())
                                            .radius(entry.radius() > 0 ? entry.radius() : 0.05f)
                                            .axis(entry.axisX(), entry.axisY(), entry.axisZ())
                                            .axisLength(entry.axisLength())
                                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                                            .build();
                                    lib.cylinderExt(cyl);
                                }
                                case RECT -> {
                                    LightInfoRectEXT rect = LightInfoRectEXT.builder()
                                            .position(entry.x(), entry.y(), entry.z())
                                            .xAxis(entry.xAxisX(), entry.xAxisY(), entry.xAxisZ())
                                            .xSize(entry.xSize())
                                            .yAxis(entry.yAxisX(), entry.yAxisY(), entry.yAxisZ())
                                            .ySize(entry.ySize())
                                            .direction(entry.directionX(), entry.directionY(), entry.directionZ())
                                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                                            .build();
                                    lib.rectExt(rect);
                                }
                                case DISK -> {
                                    LightInfoDiskEXT disk = LightInfoDiskEXT.builder()
                                            .position(entry.x(), entry.y(), entry.z())
                                            .xAxis(entry.xAxisX(), entry.xAxisY(), entry.xAxisZ())
                                            .xRadius(entry.xSize())
                                            .yAxis(entry.yAxisX(), entry.yAxisY(), entry.yAxisZ())
                                            .yRadius(entry.ySize())
                                            .direction(entry.directionX(), entry.directionY(), entry.directionZ())
                                            .volumetricRadianceScale(entry.volumetricRadianceScale())
                                            .build();
                                    lib.diskExt(disk);
                                }
                            }
                            long lightHandle = api.createLight(lib.build());
                            if (lightHandle != 0) {
                                newLights.add(lightHandle);
                            }
                        } catch (Exception e) {
                            LOGGER.debug("Failed creating block light at {},{},{}: {}", entry.x(), entry.y(), entry.z(), e.getMessage());
                        }
                    }
                    if (!newLights.isEmpty()) {
                        sectionLights.put(pos, newLights);
                    }
                }
            }
            drainedCaptures.clear();
            reflagEvictedSections();

            // Always update camera to match the rendered frame before scene submit
            if (client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
                updateCamera(client.gameRenderer.getMainCamera(), 1.0f);
            }

            // Finish entity pass and sweep off-screen shadow casters
            if (entityPass != null && entitySubmitter != null && client.level != null
                    && client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
                entityPass.finishFrame(client.level, client.getEntityRenderDispatcher(), 1.0f, entitySubmitter, client.gameRenderer.getMainCamera());
            }

            // Draw all active terrain block lights within distance cull
            int camSecX = 0, camSecY = 0, camSecZ = 0;
            boolean hasCam = false;
            if (client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
                Vec3 camPos = client.gameRenderer.getMainCamera().getPosition();
                if (camPos != null) {
                    camSecX = SectionPos.blockToSectionCoord((int) camPos.x);
                    camSecY = SectionPos.blockToSectionCoord((int) camPos.y);
                    camSecZ = SectionPos.blockToSectionCoord((int) camPos.z);
                    hasCam = true;
                }
            }

            final int maxLightSecDist = 5; // ~80 blocks Chebyshev cull radius
            for (java.util.Map.Entry<SectionPos, java.util.List<Long>> entry : sectionLights.entrySet()) {
                SectionPos sPos = entry.getKey();
                if (hasCam) {
                    int dx = Math.abs(sPos.x() - camSecX);
                    int dy = Math.abs(sPos.y() - camSecY);
                    int dz = Math.abs(sPos.z() - camSecZ);
                    if (dx > maxLightSecDist || dy > maxLightSecDist || dz > maxLightSecDist) {
                        continue;
                    }
                }
                java.util.List<Long> lights = entry.getValue();
                if (lights != null) {
                    for (int i = 0; i < lights.size(); i++) {
                        long handle = lights.get(i);
                        if (handle != 0) {
                            try { api.drawLightInstance(handle); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            // Render particles through RTX Remix
            if (particleRenderer != null && client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
                particleRenderer.render(api, client.gameRenderer.getMainCamera(), 1.0f, cutoutTerrainMaterial);
            }

            // Forward animated sprite atlas patches to Remix before submitting frame
            long pAtlas = particleRenderer != null ? particleRenderer.getParticleAtlasTexture() : 0;
            com.nvidia.rtxremix.forge.texture.AtlasAnimationForwarder.flush(api, terrainAtlasTexture, pAtlas);

            // Draw sky lights and dynamic held lights right before frame submit
            if (skyRenderer != null) {
                skyRenderer.drawLights(api);
            }
            if (heldLightManager != null) {
                heldLightManager.drawLights(api);
            }

            // Submit frame to RTX Remix runtime
            api.submit(null);
            submitFrameCalled = true;

            // Check interop images and draw raytraced world overlay on Minecraft's OpenGL framebuffer
            if (interop != null && isRtxEnabled() && client.level != null && hasTerrainMeshes()) {
                try {
                    InteropInfo interopInfo = api.dxvkGetInteropInfo();
                    interop.recreateImagesIf(interopInfo);
                    interop.waitForVk(interopInfo);
                    int remixTexture = interop.getTexture();
                    if (remixTexture > 0 && overlay != null) {
                        overlay.render(remixTexture);

                        // Draw rain and snow on top of the raytraced scene overlay
                        if (client.level != null && (client.level.isRaining() || client.level.isThundering())
                                && client.levelRenderer != null && client.gameRenderer != null) {
                            try {
                                Camera camera = client.gameRenderer.getMainCamera();
                                if (camera != null) {
                                    Vec3 camPos = camera.getPosition();
                                    renderingPostWeather = true;
                                    ((com.nvidia.rtxremix.forge.mixin.LevelRendererAccessor) client.levelRenderer)
                                            .rtxremix$renderSnowAndRain(client.gameRenderer.lightTexture(), lastPartialTicks, camPos.x, camPos.y, camPos.z);
                                }
                            } catch (Exception e) {
                                LOGGER.debug("Post-overlay weather draw skipped: {}", e.getMessage());
                            } finally {
                                renderingPostWeather = false;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Error blitting Vulkan raytraced scene to GL overlay", e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error submitting scene in RTX Remix: {}", e.getMessage(), e);
        }
    }

    public void presentFrameVk() {
        if (!isInitialized()) return;

        try {
            if (!submitFrameCalled) {
                api.submit(null);
            }

            if (interop != null) {
                InteropInfo interopInfo = api.dxvkGetInteropInfo();
                interop.recreateImagesIf(interopInfo);
                interop.waitForVk(interopInfo);
                if (client.getMainRenderTarget() != null) {
                    interop.copyFromFramebuffer(client.getMainRenderTarget().frameBufferId);
                }
                interop.signalDone();

                PresentInfo present = new PresentInfo();
                present.hwndOverride = 0;
                present.skipVulkanSwapchain = 0;
                present.interopFlipY = 1;
                present.clientDoneSemaphore_ValueToWait = interop.getClientDoneSemaphoreValue();
                present.disableCpuWaitForExternalSemaphores = 0;
                api.present(present);
            }
        } catch (RemixException e) {
            LOGGER.error("Error presenting Vulkan frame: {}", e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error presenting frame: {}", e.getMessage(), e);
        } finally {
            submitFrameCalled = false;
        }
    }

    public boolean isSceneSubmitted() {
        return submitFrameCalled;
    }

    private final java.util.List<com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.EvictedSection> evictedScratch = new java.util.ArrayList<>();

    private void reflagEvictedSections() {
        evictedScratch.clear();
        com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.drainEvictedSections(evictedScratch, 64);
        if (evictedScratch.isEmpty() || client == null || client.levelRenderer == null) return;
        for (com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.EvictedSection s : evictedScratch) {
            client.levelRenderer.setSectionDirty(s.x(), s.y(), s.z());
        }
    }

    public void unloadChunkColumn(int cx, int cz) {
        if (!isInitialized()) return;
        if (client != null && !client.isSameThread()) {
            client.execute(() -> unloadChunkColumn(cx, cz));
            return;
        }
        int minY = -8;
        int maxY = 24;
        if (client != null && client.level != null) {
            minY = client.level.getMinSection();
            maxY = client.level.getMaxSection();
        }
        for (int y = minY; y <= maxY; y++) {
            SectionPos pos = SectionPos.of(cx, y, cz);
            java.util.List<Long> oldInstances = sectionInstances.remove(pos);
            if (oldInstances != null) {
                for (Long inst : oldInstances) {
                    if (inst != null && inst != 0 && api != null) {
                        try { api.sceneInstanceRemove(inst); } catch (Exception ignored) {}
                    }
                }
            }
            java.util.List<Long> oldMeshes = sectionMeshes.remove(pos);
            if (oldMeshes != null) {
                for (Long m : oldMeshes) {
                    if (m != null && m != 0 && api != null) {
                        try { api.destroyMesh(m); } catch (Exception ignored) {}
                    }
                }
            }
            java.util.List<Long> oldLights = sectionLights.remove(pos);
            if (oldLights != null) {
                for (Long l : oldLights) {
                    if (l != null && l != 0 && api != null) {
                        try { api.destroyLight(l); } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    public void clearLevelState() {
        for (java.util.List<Long> instList : sectionInstances.values()) {
            if (instList != null) {
                for (Long inst : instList) {
                    if (inst != null && inst != 0 && api != null) {
                        try { api.sceneInstanceRemove(inst); } catch (Exception ignored) {}
                    }
                }
            }
        }
        sectionInstances.clear();

        for (java.util.List<Long> meshList : sectionMeshes.values()) {
            if (meshList != null) {
                for (Long m : meshList) {
                    if (m != null && m != 0 && api != null) {
                        try { api.destroyMesh(m); } catch (Exception ignored) {}
                    }
                }
            }
        }
        sectionMeshes.clear();

        for (java.util.List<Long> lightList : sectionLights.values()) {
            if (lightList != null) {
                for (Long l : lightList) {
                    if (l != null && l != 0 && api != null) {
                        try { api.destroyLight(l); } catch (Exception ignored) {}
                    }
                }
            }
        }
        sectionLights.clear();

        com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.clear();
        com.nvidia.rtxremix.forge.texture.AtlasAnimationForwarder.clear();
        if (skyRenderer != null && api != null) {
            skyRenderer.clearLevelState(api);
        }
        if (heldLightManager != null && api != null) {
            heldLightManager.clear(api);
        }
        if (particleRenderer != null && api != null) {
            particleRenderer.destroy(api);
        }
        if (entitySubmitter != null) {
            entitySubmitter.clearInstances();
        }
        LOGGER.info("Cleared level terrain and sky state in RTX Remix.");
    }

    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        submitFrameCalled = false;
        clearLevelState();
        if (entitySubmitter != null) {
            entitySubmitter.destroy();
            entitySubmitter = null;
        }
        if (particleRenderer != null) {
            particleRenderer.destroy(api);
            particleRenderer = null;
        }
        if (confLayerDriver != null) {
            confLayerDriver.releaseAll();
        }
        if (solidTerrainMaterial != 0) {
            try { api.destroyMaterial(solidTerrainMaterial); } catch (Exception ignored) {}
            solidTerrainMaterial = 0;
        }
        if (cutoutTerrainMaterial != 0) {
            try { api.destroyMaterial(cutoutTerrainMaterial); } catch (Exception ignored) {}
            cutoutTerrainMaterial = 0;
        }
        if (waterMaterial != 0) {
            try { api.destroyMaterial(waterMaterial); } catch (Exception ignored) {}
            waterMaterial = 0;
        }
        if (waterCameraMediumMaterial != 0) {
            try { api.destroyMaterial(waterCameraMediumMaterial); } catch (Exception ignored) {}
            waterCameraMediumMaterial = 0;
        }
        lastCameraMediumHandle = 0;
        if (translucentTerrainMaterial != 0) {
            try { api.destroyMaterial(translucentTerrainMaterial); } catch (Exception ignored) {}
            translucentTerrainMaterial = 0;
        }
        if (waterNormalTexture != 0) {
            try { api.destroyTexture(waterNormalTexture); } catch (Exception ignored) {}
            waterNormalTexture = 0;
        }
        if (waterWhiteTexture != 0) {
            try { api.destroyTexture(waterWhiteTexture); } catch (Exception ignored) {}
            waterWhiteTexture = 0;
        }
        if (terrainAtlasTexture != 0) {
            try { api.destroyTexture(terrainAtlasTexture); } catch (Exception ignored) {}
            terrainAtlasTexture = 0;
        }
        defaultTerrainMaterial = 0;
        if (interop != null) {
            interop.destroy();
            interop = null;
        }
        com.nvidia.rtxremix.forge.util.CanonicalIndexBuffer.free();
        if (api != null) {
            api.shutdown();
            api = null;
        }
        active = false;
        initialized = false;
        LOGGER.info("RTX Remix Renderer destroyed.");
    }
}
