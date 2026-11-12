/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoDistantEXT;
import com.nvidia.rtxremix.LightInfoDomeEXT;
import com.nvidia.rtxremix.LightInfoRectEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.forge.client.ForgeRemixRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Manages atmospheric sky rendering, physically based dome lights, and sky ambient lighting.
 * Automatically suppresses sky radiance and ambient rect light in subterranean biomes (Alex's Caves)
 * and deep caverns (camera Y < 0) to ensure deep underground areas stay completely dark.
 */
public class SkyRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/SkyRenderer");

    private static final float[] SKYLESS_SKY_RADIANCE = new float[]{0.0f, 0.0f, 0.0f};
    private static final float[] END_SKY_RADIANCE = new float[]{0.015f, 0.005f, 0.025f};

    private static final float SKY_AMBIENT_RECT_Y = 325.0f;
    private static final float SKY_AMBIENT_RECT_HALF_SIZE = 8000.0f;

    private static boolean enabled = true;
    private static boolean skyAmbientRectEnabled = true;
    private static float skyDomeMultiplier = 1.0f;

    private final AtmosphericSkyGenerator atmosphericGenerator = new AtmosphericSkyGenerator();
    private final float[] atmosphericMean = new float[3];

    private long skyTextureHandle = 0;
    private long skyDomeLightHandle = 0;
    private long skyAmbientRectHandle = 0;

    public SkyRenderer() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }
    public static boolean isSkyAmbientRectEnabled() { return skyAmbientRectEnabled; }
    public static void setSkyAmbientRectEnabled(boolean value) { skyAmbientRectEnabled = value; }
    public static float getSkyDomeMultiplier() { return skyDomeMultiplier; }
    public static void setSkyDomeMultiplier(float value) { skyDomeMultiplier = value; }

    public void render(RemixAPI api, ClientLevel world, Vec3 cameraPos, float tickDelta) {
        if (!enabled || api == null || world == null) return;

        DimensionSpecialEffects effects = world.effects();
        DimensionSpecialEffects.SkyType skyType = effects != null ? effects.skyType() : DimensionSpecialEffects.SkyType.NORMAL;

        // Subterranean and Alex's Caves check: suppress atmospheric sky dome when underground
        if (cameraPos != null && (cameraPos.y < 0 || isSubterraneanBiome(world, cameraPos))) {
            renderFixedSky(api, SKYLESS_SKY_RADIANCE);
            return;
        }

        if (skyType == DimensionSpecialEffects.SkyType.NORMAL) {
            renderAtmosphericSky(api, world, cameraPos, tickDelta);
        } else if (skyType == DimensionSpecialEffects.SkyType.END) {
            renderFixedSky(api, END_SKY_RADIANCE);
        } else {
            renderFixedSky(api, SKYLESS_SKY_RADIANCE);
        }
    }

    private void renderAtmosphericSky(RemixAPI api, ClientLevel world, Vec3 cameraPos, float tickDelta) {
        float timeOfDay = world.getTimeOfDay(tickDelta);
        float rain = world.getRainLevel(tickDelta);

        ByteBuffer pixels = atmosphericGenerator.generate(timeOfDay, rain, atmosphericMean);
        if (pixels != null) {
            publishSkyDome(api, pixels, skyDomeMultiplier);
            publishSkyAmbient(api, atmosphericMean[0], atmosphericMean[1], atmosphericMean[2]);
            updateSunLight(api, world, tickDelta);
        }
    }

    private long sunLightHandle = 0;

    private void updateSunLight(RemixAPI api, ClientLevel world, float tickDelta) {
        float celestialAngle = world.getTimeOfDay(tickDelta);
        float radians = celestialAngle * 2.0f * (float) Math.PI;

        // Position of the celestial body in the sky
        float sunX = (float) Math.cos(radians);
        float sunY = (float) Math.sin(radians);

        boolean isDay = sunY > 0.0f;
        float dirX, dirY, dirZ;
        float radR, radG, radB;

        if (isDay) {
            // Sun in the sky: incoming light direction points down towards the earth
            dirX = -sunX;
            dirY = -sunY;
            dirZ = 0.0f;
            float lum = 35.0f * Math.max(0.1f, sunY);
            radR = lum;
            radG = lum * 0.98f;
            radB = lum * 0.90f;
        } else {
            // Moon in the sky: incoming light direction points down towards the earth
            dirX = sunX;
            dirY = sunY; // since sunY < 0, dirY < 0 (pointing down towards the earth)
            dirZ = 0.0f;
            float lum = 2.5f * Math.max(0.1f, -sunY);
            radR = lum * 0.70f;
            radG = lum * 0.85f;
            radB = lum * 1.00f;
        }

        try {
            if (sunLightHandle != 0) {
                api.destroyLight(sunLightHandle);
                sunLightHandle = 0;
            }

            LightInfo lightInfo = LightInfo.builder()
                    .radiance(radR, radG, radB)
                    .distantExt(LightInfoDistantEXT.builder()
                            .direction(dirX, dirY, dirZ)
                            .angularDiameterDegrees(1.0f)
                            .volumetricRadianceScale(1.0f)
                            .build())
                    .build();
            sunLightHandle = api.createLight(lightInfo);
            if (sunLightHandle != 0) {
                api.drawLightInstance(sunLightHandle);
            }
        } catch (Exception e) {
            LOGGER.debug("Sun light update failed: {}", e.getMessage());
        }
    }

    private void renderFixedSky(RemixAPI api, float[] radiance) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(AtmosphericSkyGenerator.OUTPUT_BYTES).order(ByteOrder.nativeOrder());
        short r = (short) (HardcodedVertex.packHalf2x16(radiance[0], 0.0f) & 0xFFFF);
        short g = (short) (HardcodedVertex.packHalf2x16(radiance[1], 0.0f) & 0xFFFF);
        short b = (short) (HardcodedVertex.packHalf2x16(radiance[2], 0.0f) & 0xFFFF);
        short a = (short) (HardcodedVertex.packHalf2x16(1.0f, 0.0f) & 0xFFFF);
        for (int i = 0; i < AtmosphericSkyGenerator.WIDTH * AtmosphericSkyGenerator.HEIGHT; i++) {
            pixels.putShort(r).putShort(g).putShort(b).putShort(a);
        }
        pixels.flip();

        publishSkyDome(api, pixels, 1.0f);
        publishSkyAmbient(api, radiance[0], radiance[1], radiance[2]);
    }

    private void publishSkyDome(RemixAPI api, ByteBuffer pixels, float multiplier) {
        try {
            if (skyTextureHandle != 0) {
                try { api.destroyTexture(skyTextureHandle); } catch (Exception ignored) {}
                skyTextureHandle = 0;
            }
            if (skyDomeLightHandle != 0) {
                try { api.destroyLight(skyDomeLightHandle); } catch (Exception ignored) {}
                skyDomeLightHandle = 0;
            }

            TextureFromMemoryInfo texInfo = TextureFromMemoryInfo.builder()
                    .hash(0x51C7_53C1_0000_0001L)
                    .size(AtmosphericSkyGenerator.WIDTH, AtmosphericSkyGenerator.HEIGHT)
                    .format(TextureFormat.RGBA16_SFLOAT)
                    .pixelData(pixels)
                    .build();
            skyTextureHandle = api.createTextureFromMemory(texInfo);

            if (skyTextureHandle != 0) {
                LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
                domeExt.setColorTexture(skyTextureHandle);
                LightInfo lightInfo = LightInfo.builder()
                        .radiance(multiplier, multiplier, multiplier)
                        .domeExt(domeExt)
                        .build();
                skyDomeLightHandle = api.createLight(lightInfo);
            }

            if (skyDomeLightHandle != 0) {
                api.drawLightInstance(skyDomeLightHandle);
            }
        } catch (Exception e) {
            LOGGER.debug("Sky dome draw failed: {}", e.getMessage());
        }
    }

    private void publishSkyAmbient(RemixAPI api, float r, float g, float b) {
        if (!skyAmbientRectEnabled || (r <= 0.0001f && g <= 0.0001f && b <= 0.0001f)) {
            return;
        }

        try {
            if (skyAmbientRectHandle != 0) {
                try { api.destroyLight(skyAmbientRectHandle); } catch (Exception ignored) {}
                skyAmbientRectHandle = 0;
            }

            LightInfoRectEXT rectExt = LightInfoRectEXT.builder()
                    .position(0.0f, SKY_AMBIENT_RECT_Y, 0.0f)
                    .xAxis(1, 0, 0)
                    .xSize(SKY_AMBIENT_RECT_HALF_SIZE)
                    .yAxis(0, 0, 1)
                    .ySize(SKY_AMBIENT_RECT_HALF_SIZE)
                    .direction(0, -1, 0)
                    .build();
            LightInfo lightInfo = LightInfo.builder()
                    .radiance(r, g, b)
                    .rectExt(rectExt)
                    .build();
            skyAmbientRectHandle = api.createLight(lightInfo);

            if (skyAmbientRectHandle != 0) {
                api.drawLightInstance(skyAmbientRectHandle);
            }
        } catch (Exception e) {
            LOGGER.debug("Sky ambient rect draw failed: {}", e.getMessage());
        }
    }

    private static boolean isSubterraneanBiome(ClientLevel world, Vec3 cameraPos) {
        if (world == null || cameraPos == null) return false;
        try {
            BlockPos pos = BlockPos.containing(cameraPos.x, cameraPos.y, cameraPos.z);
            ResourceLocation biomeId = world.getBiome(pos).unwrapKey().map(ResourceKey::location).orElse(null);
            if (biomeId == null) return false;
            String ns = biomeId.getNamespace();
            String path = biomeId.getPath();
            return ns.equals("alexscaves") || path.contains("cave") || path.contains("cavern") || path.contains("underground");
        } catch (Exception ignored) {
            return false;
        }
    }

    public void cleanup() {
        skyTextureHandle = 0;
        skyDomeLightHandle = 0;
        skyAmbientRectHandle = 0;
    }
}
