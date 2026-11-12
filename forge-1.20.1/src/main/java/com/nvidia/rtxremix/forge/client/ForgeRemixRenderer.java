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
    private final com.nvidia.rtxremix.forge.logic.ConfLayerDriver confLayerDriver = new com.nvidia.rtxremix.forge.logic.ConfLayerDriver();
    private final java.util.List<com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.QueuedCapture> drainedCaptures = new java.util.ArrayList<>();
    private RemixAPI api;
    private GlInterop interop;
    private TextureOverlay overlay;
    private com.nvidia.rtxremix.forge.entity.RemixEntitySubmitter entitySubmitter;
    private com.nvidia.rtxremix.forge.entity.RemixEntityPass entityPass;

    private boolean initialized = false;
    private boolean active = false;
    private long frameIndex = 0;

    private ForgeRemixRenderer() {}

    public static ForgeRemixRenderer getInstance() {
        return INSTANCE;
    }

    public static boolean isRtxEnabled() {
        return INSTANCE.active && INSTANCE.initialized && INSTANCE.api != null;
    }

    public com.nvidia.rtxremix.forge.sky.SkyRenderer getSkyRenderer() {
        return skyRenderer;
    }

    private boolean menuOpen = false;

    public synchronized boolean initializeIfSupported() {
        if (initialized && api != null) {
            return true;
        }

        long windowHandle = client.getWindow().getWindow();
        if (windowHandle == 0) {
            LOGGER.warn("GLFW window not yet ready for RTX Remix initialization.");
            return false;
        }

        long hwnd = GLFWNativeWin32.glfwGetWin32Window(windowHandle);
        LOGGER.info("Initializing RTX Remix API against Win32 HWND: 0x{}", Long.toHexString(hwnd));

        try {
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
                    .build();

            api.startup(startup);
            overlay = new TextureOverlay();
            entitySubmitter = new com.nvidia.rtxremix.forge.entity.RemixEntitySubmitter(api);
            entityPass = new com.nvidia.rtxremix.forge.entity.RemixEntityPass();
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

    public void toggleMenu() {
        if (!isRtxEnabled()) {
            LOGGER.warn("Cannot toggle RTX Remix menu: renderer not initialized or not active.");
            return;
        }
        menuOpen = !menuOpen;
        try {
            api.setConfigVariable("rtx.showUI", menuOpen ? "2" : "0");
            LOGGER.info("RTX Remix menu toggled: {}", menuOpen ? "OPEN (Developer)" : "CLOSED");
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    private final java.util.Map<SectionPos, Long> sectionInstances = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<SectionPos, java.util.List<Long>> sectionMeshes = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean hasTerrainMeshes() {
        return !sectionInstances.isEmpty();
    }

    public void beginFrame(Camera camera, float partialTicks) {
        if (!isInitialized() || !active) return;

        frameIndex++;
        Vec3 pos = camera.getPosition();
        updateCamera(camera, partialTicks);

        if (client.level != null) {
            skyRenderer.render(api, client.level, pos, partialTicks);
            boolean isUnderwater = camera.getFluidInCamera() == net.minecraft.world.level.material.FogType.WATER;
            confLayerDriver.update(client.level, pos, isUnderwater, 1.0f / 60.0f);
        }
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
        entityPass.beginFrame(entitySubmitter);
        entityPass.captureAndSubmit(entity, client.getEntityRenderDispatcher(), tickDelta, entitySubmitter);
        return true;
    }

    public void submitScene() {
        if (!isInitialized() || !active) return;

        try {
            // Drain captured block terrain data and submit to Remix
            drainedCaptures.clear();
            com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.drain(drainedCaptures);
            for (com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue.QueuedCapture capture : drainedCaptures) {
                if (capture == null || capture.data() == null) continue;
                SectionBlockData blockData = capture.data();
                SectionMeshData meshData = blockData.meshData();
                if (meshData == null) continue;

                SectionPos pos = blockData.sectionPos();

                // Clean up previous instance and meshes for this section
                Long oldInstance = sectionInstances.remove(pos);
                if (oldInstance != null && oldInstance != 0) {
                    try { api.sceneInstanceRemove(oldInstance); } catch (Exception ignored) {}
                }
                java.util.List<Long> oldMeshes = sectionMeshes.remove(pos);
                if (oldMeshes != null) {
                    for (Long m : oldMeshes) {
                        if (m != null && m != 0) {
                            try { api.destroyMesh(m); } catch (Exception ignored) {}
                        }
                    }
                }

                if (!meshData.isEmpty()) {
                    java.util.List<Long> newMeshes = new java.util.ArrayList<>();
                    Transform sectionTransform = new Transform();
                    sectionTransform.setTranslation(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());

                    for (SectionMeshData.Surface surface : meshData.surfaces()) {
                        int vCount = surface.vertexCount();
                        int iCount = surface.indexCount();
                        if (vCount <= 0 || iCount <= 0) continue;

                        java.nio.ByteBuffer vBuf = surface.vertexBuffer();
                        java.nio.ByteBuffer iBuf = com.nvidia.rtxremix.forge.util.DirectBufferPool.acquire(iCount * Integer.BYTES);
                        try {
                            int quads = vCount / 4;
                            for (int q = 0; q < quads; q++) {
                                int base = q * 4;
                                iBuf.putInt(base);
                                iBuf.putInt(base + 1);
                                iBuf.putInt(base + 2);
                                iBuf.putInt(base);
                                iBuf.putInt(base + 2);
                                iBuf.putInt(base + 3);
                            }
                            iBuf.flip();

                            MeshInfoSurfaceTriangles surf = new MeshInfoSurfaceTriangles();
                            surf.vertexBuffer = vBuf;
                            surf.vertexCount = vCount;
                            surf.indexBuffer = iBuf;
                            surf.indexCount = iCount;
                            surf.material = 0;

                            long hash = (((long) pos.hashCode()) << 32) ^ surface.category() ^ vCount;
                            MeshInfo mi = MeshInfo.builder()
                                    .hash(hash)
                                    .surfaces(surf)
                                    .build();

                            long mHandle = api.createMesh(mi);
                            if (mHandle != 0) {
                                newMeshes.add(mHandle);
                                long instHandle = api.sceneInstanceAdd(mHandle, sectionTransform.matrix, InstanceCategoryBit.WORLD_UI, 0, 0);
                                if (instHandle != 0) {
                                    sectionInstances.put(pos, instHandle);
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.error("Failed creating terrain mesh for section {}", pos, e);
                        } finally {
                            com.nvidia.rtxremix.forge.util.DirectBufferPool.release(iBuf);
                        }
                    }
                    if (!newMeshes.isEmpty()) {
                        sectionMeshes.put(pos, newMeshes);
                    }
                }
                meshData.release();
            }
            drainedCaptures.clear();

            // Finish entity pass and sweep off-screen shadow casters
            if (entityPass != null && entitySubmitter != null && client.level != null
                    && client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
                entityPass.finishFrame(client.level, client.getEntityRenderDispatcher(), 1.0f, entitySubmitter, client.gameRenderer.getMainCamera());
            }

            // Check interop images and draw overlay
            if (interop != null && interop.getTexture() > 0) {
                overlay.render(interop.getTexture());
            }
        } catch (Exception e) {
            LOGGER.error("Error submitting scene in RTX Remix: {}", e.getMessage(), e);
        }
    }

    public void presentFrameVk() {
        if (!isInitialized() || !active) return;

        try {
            PresentInfo present = new PresentInfo();
            present.interopFlipY = 1;
            api.present(present);
        } catch (RemixException e) {
            LOGGER.error("Error presenting Vulkan frame: {}", e.getMessage(), e);
        }
    }

    public void destroy() {
        if (entitySubmitter != null) {
            entitySubmitter.destroy();
            entitySubmitter = null;
        }
        if (confLayerDriver != null) {
            confLayerDriver.releaseAll();
        }
        if (interop != null) {
            interop.destroy();
            interop = null;
        }
        if (api != null) {
            api.shutdown();
            api = null;
        }
        active = false;
        initialized = false;
        LOGGER.info("RTX Remix Renderer destroyed.");
    }
}
