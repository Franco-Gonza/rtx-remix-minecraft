/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles client lifecycle and gameplay event hooks on Forge.
 */
public final class ClientEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/ClientEvents");
    private static final ClientEvents INSTANCE = new ClientEvents();

    private boolean initialized = false;

    private ClientEvents() {}

    public static ClientEvents getInstance() {
        return INSTANCE;
    }

    public void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("RTX Remix ClientEvents listener registered.");
    }

    @SubscribeEvent
    public void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("Player joined level. Initializing RTX Remix world state...");
    }

    @SubscribeEvent
    public void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.info("Player disconnected from level. Tearing down RTX Remix world state...");
        ForgeRemixRenderer.getInstance().clearLevelState();
    }

    @SubscribeEvent
    public void onGameShuttingDown(net.minecraftforge.event.GameShuttingDownEvent event) {
        LOGGER.info("GameShuttingDownEvent received: destroying RTX Remix renderer...");
        ForgeRemixRenderer.getInstance().destroy();
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() != null && event.getLevel().isClientSide() && event.getChunk() != null) {
            net.minecraft.world.level.ChunkPos pos = event.getChunk().getPos();
            ForgeRemixRenderer.getInstance().unloadChunkColumn(pos.x, pos.z);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // Per-frame client tick logic
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        // Hooks into world rendering stages (AFTER_SOLID_BLOCKS, AFTER_TRANSLUCENT_BLOCKS, etc.)
    }

    @SubscribeEvent
    public void onKeyInput(net.minecraftforge.client.event.InputEvent.Key event) {
        if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS && event.getKey() == org.lwjgl.glfw.GLFW.GLFW_KEY_X) {
            long window = Minecraft.getInstance().getWindow().getWindow();
            boolean altDown = (event.getModifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_ALT) != 0
                    || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                    || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean ctrlDown = (event.getModifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0
                    || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                    || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

            if (altDown && !ctrlDown) {
                LOGGER.info("Alt+X detected via Forge InputEvent; toggling RTX Remix menu");
                ForgeRemixRenderer.getInstance().toggleMenu();
            }
        }
    }

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof OptionsScreen optionsScreen) {
            int buttonWidth = 150;
            int buttonHeight = 20;
            int x = optionsScreen.width / 2 - 155;
            int y = optionsScreen.height / 6 + 144 - 6;

            Button rtxSettingsButton = Button.builder(
                Component.literal("RTX Remix Settings..."),
                button -> {
                    LOGGER.info("Opening RTX Remix Settings from Options menu...");
                    ForgeRemixRenderer.getInstance().toggleMenu();
                }
            ).bounds(x, y, buttonWidth, buttonHeight).build();

            event.addListener(rtxSettingsButton);
        }
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            net.minecraft.commands.Commands.literal("rtx")
                .then(net.minecraft.commands.Commands.literal("menu")
                    .executes(ctx -> {
                        ForgeRemixRenderer.getInstance().toggleMenu();
                        return 1;
                    }))
                .then(net.minecraft.commands.Commands.literal("on")
                    .executes(ctx -> {
                        ForgeRemixRenderer.getInstance().setActive(true);
                        ctx.getSource().sendSuccess(() -> Component.literal("§a[RTX Remix] Enabled path tracing."), false);
                        return 1;
                    }))
                .then(net.minecraft.commands.Commands.literal("off")
                    .executes(ctx -> {
                        ForgeRemixRenderer.getInstance().setActive(false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§c[RTX Remix] Disabled path tracing (Vanilla raster)."), false);
                        return 1;
                    }))
                .then(net.minecraft.commands.Commands.literal("reload")
                    .executes(ctx -> {
                        ForgeRemixRenderer.getInstance().clearLevelState();
                        ForgeRemixRenderer.getInstance().updateTerrainMaterialIfReady();
                        if (Minecraft.getInstance().levelRenderer != null) {
                            Minecraft.getInstance().levelRenderer.allChanged();
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("§e[RTX Remix] Reloaded scene, terrain atlas & chunks."), false);
                        return 1;
                    }))
        );
    }
}
