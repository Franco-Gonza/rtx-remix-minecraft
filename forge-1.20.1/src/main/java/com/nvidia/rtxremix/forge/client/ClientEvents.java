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
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof OptionsScreen optionsScreen) {
            // Inject the RTX Remix Settings button into Minecraft's Options screen
            int buttonWidth = 150;
            int buttonHeight = 20;
            int x = optionsScreen.width / 2 - 155;
            int y = optionsScreen.height / 6 + 144 - 6;

            Button rtxSettingsButton = Button.builder(
                Component.literal("RTX Remix Settings..."),
                button -> {
                    LOGGER.info("Opening RTX Remix Settings screen...");
                    // Opens settings screen
                }
            ).bounds(x, y, buttonWidth, buttonHeight).build();

            event.addListener(rtxSettingsButton);
        }
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // Register /rtx command tree
    }
}
