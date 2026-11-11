/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.logic;

import com.nvidia.rtxremix.RemixAPI;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Drives RTX Remix config option-layers based on active gameplay state:
 * underwater, view distance, time of day, weather, and modded/vanilla biomes.
 */
public final class ConfLayerDriver {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfLayerDriver.class);

    private static final float SMOOTHING_FACTOR = 2.0f;
    private static final float SNAP_EPSILON = 0.001f;
    private static final float SEND_EPSILON = 1.0e-4f;

    private static final class ActiveLayer {
        final long handle;
        final float blendThreshold;
        float target = 0.0f;
        float current = 0.0f;
        float lastSent = -1.0f;

        ActiveLayer(long handle, float blendThreshold) {
            this.handle = handle;
            this.blendThreshold = blendThreshold;
        }
    }

    private final Map<String, ActiveLayer> layers = new HashMap<>();
    private RemixAPI api;
    private Path gameDir;
    private boolean acquired = false;

    public boolean acquireAll(RemixAPI api, Path gameDir) {
        this.api = api;
        this.gameDir = gameDir;
        if (api == null || !api.isConfigLayersSupported()) {
            return false;
        }

        Path confsDir = gameDir.resolve("rtx-remix").resolve("mods").resolve("minecraft-logic").resolve("confs");
        if (!Files.exists(confsDir)) {
            LOGGER.info("Config layer directory not present at {}; skipping option layer acquisition.", confsDir);
            return false;
        }

        try {
            // Acquire base layers
            acquireLayer(LogicChannels.UNDERWATER, confsDir.resolve("water.conf"), 10.0f, 0.05f);
            acquireLayer(LogicChannels.TIME_DAWN, confsDir.resolve("time").resolve("dawn.conf"), 5.0f, 0.01f);
            acquireLayer(LogicChannels.TIME_DAY, confsDir.resolve("time").resolve("day.conf"), 5.0f, 0.01f);
            acquireLayer(LogicChannels.TIME_DUSK, confsDir.resolve("time").resolve("dusk.conf"), 5.0f, 0.01f);
            acquireLayer(LogicChannels.TIME_NIGHT, confsDir.resolve("time").resolve("night.conf"), 5.0f, 0.01f);
            acquireLayer(LogicChannels.WEATHER_RAIN, confsDir.resolve("weather").resolve("rain.conf"), 6.0f, 0.01f);
            acquireLayer(LogicChannels.WEATHER_THUNDER, confsDir.resolve("weather").resolve("thunder.conf"), 6.5f, 0.01f);

            // Discover biome layers
            Path biomesDir = confsDir.resolve("biomes");
            if (Files.isDirectory(biomesDir)) {
                try (Stream<Path> stream = Files.list(biomesDir)) {
                    stream.filter(p -> p.toString().endsWith(".conf")).forEach(p -> {
                        String filename = p.getFileName().toString();
                        String biomeName = filename.substring(0, filename.length() - 5);
                        acquireLayer(LogicChannels.BIOME_PREFIX + biomeName, p, 4.0f, 0.01f);
                    });
                }
            }
            acquired = true;
            LOGGER.info("Successfully acquired {} RTX Remix config layers.", layers.size());
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to acquire config option layers: {}", e.getMessage(), e);
            releaseAll();
            return false;
        }
    }

    private void acquireLayer(String channel, Path confFile, float priority, float blendThreshold) {
        if (!Files.exists(confFile)) return;
        try {
            long handle = api.acquireConfigLayer(confFile.toAbsolutePath().toString(), priority, blendThreshold);
            if (handle != 0) {
                layers.put(channel, new ActiveLayer(handle, blendThreshold));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to acquire layer {}: {}", channel, e.getMessage());
        }
    }

    public void update(ClientLevel level, Vec3 cameraPos, boolean isUnderwater, float dtSeconds) {
        if (!acquired || api == null || level == null) return;

        // 1. Underwater
        setTarget(LogicChannels.UNDERWATER, isUnderwater ? 1.0f : 0.0f);

        // 2. Weather
        float rain = level.getRainLevel(1.0f);
        float thunder = level.getThunderLevel(1.0f);
        setTarget(LogicChannels.WEATHER_RAIN, rain);
        setTarget(LogicChannels.WEATHER_THUNDER, thunder);

        // 3. Time of day
        long time = level.getDayTime() % 24000L;
        computeTimeTargets(time);

        // 4. Biome (including modded biomes like alexscaves:*, biomesoplenty:*)
        if (cameraPos != null) {
            BlockPos pos = BlockPos.containing(cameraPos.x, cameraPos.y, cameraPos.z);
            String activeBiomeChannel = level.getBiome(pos).unwrapKey()
                    .map(key -> {
                        String ns = key.location().getNamespace();
                        String path = key.location().getPath();
                        if ("minecraft".equals(ns)) {
                            return LogicChannels.BIOME_PREFIX + path;
                        } else {
                            return LogicChannels.BIOME_PREFIX + ns + "_" + path;
                        }
                    })
                    .orElse(null);

            for (Map.Entry<String, ActiveLayer> entry : layers.entrySet()) {
                if (entry.getKey().startsWith(LogicChannels.BIOME_PREFIX)) {
                    boolean isActive = entry.getKey().equals(activeBiomeChannel);
                    entry.getValue().target = isActive ? 1.0f : 0.0f;
                }
            }
        }

        // Apply smoothing and flush to native runtime
        flush(dtSeconds);
    }

    private void computeTimeTargets(long time) {
        float dawn = 0, day = 0, dusk = 0, night = 0;
        if (time < 1000) {
            dawn = 1.0f - (time / 1000.0f);
            day = time / 1000.0f;
        } else if (time < 11000) {
            day = 1.0f;
        } else if (time < 13000) {
            float t = (time - 11000) / 2000.0f;
            day = 1.0f - t;
            dusk = t;
        } else if (time < 14000) {
            float t = (time - 13000) / 1000.0f;
            dusk = 1.0f - t;
            night = t;
        } else if (time < 22000) {
            night = 1.0f;
        } else if (time < 23000) {
            float t = (time - 22000) / 1000.0f;
            night = 1.0f - t;
            dawn = t;
        } else {
            dawn = 1.0f;
        }

        setTarget(LogicChannels.TIME_DAWN, dawn);
        setTarget(LogicChannels.TIME_DAY, day);
        setTarget(LogicChannels.TIME_DUSK, dusk);
        setTarget(LogicChannels.TIME_NIGHT, night);
    }

    private void setTarget(String channel, float target) {
        ActiveLayer layer = layers.get(channel);
        if (layer != null) {
            layer.target = target;
        }
    }

    private void flush(float dtSeconds) {
        float alpha = 1.0f - (float) Math.exp(-SMOOTHING_FACTOR * dtSeconds);

        for (ActiveLayer layer : layers.values()) {
            float gap = layer.target - layer.current;
            if (Math.abs(gap) < SNAP_EPSILON) {
                layer.current = layer.target;
            } else {
                layer.current += gap * alpha;
            }

            if (Math.abs(layer.current - layer.lastSent) >= SEND_EPSILON) {
                try {
                    api.setConfigLayerBlending(layer.handle, layer.current, layer.blendThreshold);
                    layer.lastSent = layer.current;
                } catch (Exception e) {
                    LOGGER.debug("Failed to set layer blending for handle 0x{}: {}", Long.toHexString(layer.handle), e.getMessage());
                }
            }
        }
    }

    public void releaseAll() {
        if (api != null) {
            for (ActiveLayer layer : layers.values()) {
                try {
                    api.releaseConfigLayer(layer.handle);
                } catch (Exception ignored) {}
            }
        }
        layers.clear();
        acquired = false;
    }
}
