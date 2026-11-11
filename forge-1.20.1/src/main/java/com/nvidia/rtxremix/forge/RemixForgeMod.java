/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.forge.client.ClientEvents;
import com.nvidia.rtxremix.forge.runtime.ForgeNativeExtractor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NVIDIA RTX Remix Forge 1.20.1 Mod Entrypoint.
 * Configured as strictly 100% Client-Side with IGNORESERVERONLY display test
 * so players can join any Forge 1.20.1 multiplayer server without requiring server installation.
 */
@Mod(RemixForgeMod.MOD_ID)
public final class RemixForgeMod {
    public static final String MOD_ID = "rtxremix";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public RemixForgeMod() {
        // Enforce 100% Client-Side handshake: ignore server-only requirements
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
            () -> new IExtensionPoint.DisplayTest(
                () -> IExtensionPoint.DisplayTest.IGNORESERVERONLY,
                (remoteVersion, isFromServer) -> true
            )
        );

        if (FMLEnvironment.dist != Dist.CLIENT) {
            LOGGER.warn("NVIDIA RTX Remix is a client-side rendering mod and is inactive on dedicated servers.");
            return;
        }

        LOGGER.info("Initializing NVIDIA RTX Remix for Forge 1.20.1 (Client-Side)...");

        // Extract native runtime (remix-sdk/ and rtx.conf)
        ForgeNativeExtractor.ensureExtracted();

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onClientSetup);

        // Register client gameplay and render hooks on the Forge Event Bus
        MinecraftForge.EVENT_BUS.register(ClientEvents.getInstance());
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientEvents.getInstance().init();
            LOGGER.info("NVIDIA RTX Remix Forge 1.20.1 client setup complete.");
        });
    }
}
