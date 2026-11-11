/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.CameraInfo;
import com.nvidia.rtxremix.CameraType;
import com.nvidia.rtxremix.PresentInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.StartupInfo;
import com.nvidia.rtxremix.forge.gl.GlInterop;
import com.nvidia.rtxremix.forge.gl.TextureOverlay;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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
    private RemixAPI api;
    private GlInterop interop;
    private TextureOverlay overlay;

    private boolean initialized = false;
    private boolean active = false;
    private long frameIndex = 0;

    private ForgeRemixRenderer() {}

    public static ForgeRemixRenderer getInstance() {
        return INSTANCE;
    }

    public synchronized boolean initializeIfSupported() {
        if (initialized) {
            return api != null;
        }

        initialized = true;
        long windowHandle = client.getWindow().getWindow();
        if (windowHandle == 0) {
            LOGGER.warn("GLFW window not yet ready for RTX Remix initialization.");
            initialized = false;
            return false;
        }

        long hwnd = GLFWNativeWin32.glfwGetWin32Window(windowHandle);
        LOGGER.info("Initializing RTX Remix API against Win32 HWND: 0x{}", Long.toHexString(hwnd));

        try {
            net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
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
            active = true;
            LOGGER.info("NVIDIA RTX Remix initialized successfully on Forge 1.20.1.");
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize RTX Remix runtime: {}", e.getMessage(), e);
            api = null;
            active = false;
            return false;
        }
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

    public void beginFrame(Camera camera, float partialTicks) {
        if (!isInitialized() || !active) return;

        frameIndex++;
        // Update camera pose
        Vec3 pos = camera.getPosition();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();

        // Setup camera info in Remix
    }

    public void submitScene() {
        if (!isInitialized() || !active) return;

        try {
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
