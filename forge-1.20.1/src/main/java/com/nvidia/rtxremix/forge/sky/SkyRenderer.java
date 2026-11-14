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
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
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

    private final TexturePatch atmosphericTexturePatch = new TexturePatch();
    private final TextureBatchEntry atmosphericTextureEntry = new TextureBatchEntry();
    private final TexturePatchInfo atmosphericTextureUpdate = new TexturePatchInfo();

    private long skyTextureHandle = 0;
    private long skyDomeLightHandle = 0;
    private long skyAmbientRectHandle = 0;
    private long sunLightHandle = 0;

    public SkyRenderer() {
        atmosphericTexturePatch.width = AtmosphericSkyGenerator.WIDTH;
        atmosphericTexturePatch.height = AtmosphericSkyGenerator.HEIGHT;
        atmosphericTexturePatch.rowPitch = AtmosphericSkyGenerator.WIDTH * 4 * Short.BYTES;
        atmosphericTexturePatch.format = TextureFormat.RGBA16_SFLOAT;
        atmosphericTextureEntry.patches = new TexturePatch[]{atmosphericTexturePatch};
        atmosphericTextureUpdate.entries = new TextureBatchEntry[]{atmosphericTextureEntry};
    }

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

    private final ByteBuffer fixedSkyBuffer = ByteBuffer.allocateDirect(AtmosphericSkyGenerator.OUTPUT_BYTES).order(ByteOrder.nativeOrder());
    private float lastFixedR = -1.0f;
    private float lastFixedG = -1.0f;
    private float lastFixedB = -1.0f;

    private void renderAtmosphericSky(RemixAPI api, ClientLevel world, Vec3 cameraPos, float tickDelta) {
        lastFixedR = -1.0f;
        float timeOfDay = world.getTimeOfDay(tickDelta);
        float rain = world.getRainLevel(tickDelta);

        ByteBuffer pixels = atmosphericGenerator.generate(timeOfDay, rain, atmosphericMean);
        if (pixels != null) {
            publishSkyDome(api, pixels, skyDomeMultiplier);
            publishSkyAmbient(api, atmosphericMean[0], atmosphericMean[1], atmosphericMean[2]);
            updateSunLight(api, world, tickDelta);
        }
    }

    private void updateSunLight(RemixAPI api, ClientLevel world, float tickDelta) {
        float skyAngle = world.getTimeOfDay(tickDelta);
        float angle = (float) (skyAngle * 2.0 * Math.PI + Math.PI / 2.0);
        float sunX = (float) Math.cos(angle);
        float sunY = (float) Math.sin(angle);
        float sunZ = 0.0f;

        float cr = (float) Math.cos(Math.toRadians(35.0f));
        float sr = (float) Math.sin(Math.toRadians(35.0f));
        float ny = sunY * cr - sunZ * sr;
        float nz = sunY * sr + sunZ * cr;
        float nx = sunX;

        boolean isDay = ny > 0.0f;
        float dirX, dirY, dirZ;
        float radR, radG, radB;

        if (isDay) {
            // Incoming light direction points down from the sun
            dirX = -nx;
            dirY = -ny;
            dirZ = -nz;
            float lum = 32.0f * Math.max(0.05f, ny);
            float opticalDepth = 1.0f / Math.max(0.05f, ny);
            radR = lum * (float) Math.exp(-0.06f * opticalDepth);
            radG = lum * (float) Math.exp(-0.12f * opticalDepth);
            radB = lum * (float) Math.exp(-0.25f * opticalDepth);
        } else {
            // Incoming light direction points down from the moon
            dirX = nx;
            dirY = ny; // ny is negative, so dirY is negative (pointing down)
            dirZ = nz;
            float lum = 0.08f * Math.max(0.05f, -ny);
            radR = lum * 0.85f;
            radG = lum * 0.90f;
            radB = lum * 1.00f;
        }

        try {
            LightInfo lightInfo = LightInfo.builder()
                    .hash(0x5355_4E5F_4C49_4748L) // "SUN_LIGH"
                    .radiance(radR, radG, radB)
                    .distantExt(LightInfoDistantEXT.builder()
                            .direction(dirX, dirY, dirZ)
                            .angularDiameterDegrees(1.0f)
                            .volumetricRadianceScale(1.0f)
                            .build())
                    .build();
            long newHandle = api.createLight(lightInfo);
            if (sunLightHandle != 0 && sunLightHandle != newHandle) {
                try { api.destroyLight(sunLightHandle); } catch (Exception ignored) {}
            }
            sunLightHandle = newHandle;
            if (sunLightHandle != 0) {
                api.drawLightInstance(sunLightHandle);
            }
        } catch (Exception e) {
            LOGGER.debug("Sun light update failed: {}", e.getMessage());
        }
    }

    private void renderFixedSky(RemixAPI api, float[] radiance) {
        float r = radiance[0], g = radiance[1], b = radiance[2];
        if (skyTextureHandle == 0 || r != lastFixedR || g != lastFixedG || b != lastFixedB) {
            fixedSkyBuffer.clear();
            short hr = (short) (HardcodedVertex.packHalf2x16(r, 0.0f) & 0xFFFF);
            short hg = (short) (HardcodedVertex.packHalf2x16(g, 0.0f) & 0xFFFF);
            short hb = (short) (HardcodedVertex.packHalf2x16(b, 0.0f) & 0xFFFF);
            short ha = (short) (HardcodedVertex.packHalf2x16(1.0f, 0.0f) & 0xFFFF);

            for (int i = 0; i < AtmosphericSkyGenerator.WIDTH * AtmosphericSkyGenerator.HEIGHT; i++) {
                fixedSkyBuffer.putShort(hr).putShort(hg).putShort(hb).putShort(ha);
            }
            fixedSkyBuffer.flip();

            publishSkyDome(api, fixedSkyBuffer, 1.0f);
            lastFixedR = r;
            lastFixedG = g;
            lastFixedB = b;
        } else if (skyDomeLightHandle != 0) {
            try {
                api.drawLightInstance(skyDomeLightHandle);
            } catch (Exception ignored) {}
        }

        publishSkyAmbient(api, r, g, b);
    }

    private void publishSkyDome(RemixAPI api, ByteBuffer pixels, float multiplier) {
        try {
            if (skyTextureHandle == 0) {
                TextureFromMemoryInfo texInfo = TextureFromMemoryInfo.builder()
                        .hash(0x51C7_53C1_0000_0001L)
                        .size(AtmosphericSkyGenerator.WIDTH, AtmosphericSkyGenerator.HEIGHT)
                        .format(TextureFormat.RGBA16_SFLOAT)
                        .pixelData(pixels)
                        .build();
                skyTextureHandle = api.createTextureFromMemory(texInfo);

                LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
                domeExt.setColorTexture(skyTextureHandle);
                LightInfo lightInfo = LightInfo.builder()
                        .hash(0x534B_595F_444F_4D45L) // "SKY_DOME"
                        .radiance(multiplier, multiplier, multiplier)
                        .domeExt(domeExt)
                        .build();
                skyDomeLightHandle = api.createLight(lightInfo);
            } else {
                atmosphericTexturePatch.pixelData = pixels;
                atmosphericTextureEntry.handle = skyTextureHandle;
                api.updateTextureFromMemory(atmosphericTextureUpdate);
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
            LightInfoRectEXT rectExt = LightInfoRectEXT.builder()
                    .position(0.0f, SKY_AMBIENT_RECT_Y, 0.0f)
                    .xAxis(1, 0, 0)
                    .xSize(SKY_AMBIENT_RECT_HALF_SIZE)
                    .yAxis(0, 0, 1)
                    .ySize(SKY_AMBIENT_RECT_HALF_SIZE)
                    .direction(0, -1, 0)
                    .build();
            LightInfo lightInfo = LightInfo.builder()
                    .hash(0x534B_595F_414D_4249L) // "SKY_AMBI"
                    .radiance(r, g, b)
                    .rectExt(rectExt)
                    .build();
            long newHandle = api.createLight(lightInfo);
            if (skyAmbientRectHandle != 0 && skyAmbientRectHandle != newHandle) {
                try { api.destroyLight(skyAmbientRectHandle); } catch (Exception ignored) {}
            }
            skyAmbientRectHandle = newHandle;

            if (skyAmbientRectHandle != 0) {
                api.drawLightInstance(skyAmbientRectHandle);
            }
        } catch (Exception e) {
            LOGGER.debug("Sky ambient rect draw failed: {}", e.getMessage());
        }
    }

    public void clearLevelState(RemixAPI api) {
        lastFixedR = -1.0f;
        lastFixedG = -1.0f;
        lastFixedB = -1.0f;
        if (api == null) return;
        if (sunLightHandle != 0) {
            try { api.destroyLight(sunLightHandle); } catch (Exception ignored) {}
            sunLightHandle = 0;
        }
        if (skyAmbientRectHandle != 0) {
            try { api.destroyLight(skyAmbientRectHandle); } catch (Exception ignored) {}
            skyAmbientRectHandle = 0;
        }
        if (skyDomeLightHandle != 0) {
            try { api.destroyLight(skyDomeLightHandle); } catch (Exception ignored) {}
            skyDomeLightHandle = 0;
        }
        if (skyTextureHandle != 0) {
            try { api.destroyTexture(skyTextureHandle); } catch (Exception ignored) {}
            skyTextureHandle = 0;
        }
    }

    private static boolean isSubterraneanBiome(ClientLevel world, Vec3 cameraPos) {
        if (world == null || cameraPos == null) return false;
        try {
            BlockPos pos = BlockPos.containing(cameraPos);
            ResourceKey<?> biomeKey = world.registryAccess()
                    .registryOrThrow(Registries.BIOME)
                    .getResourceKey(world.getBiome(pos).value())
                    .orElse(null);

            if (biomeKey == null) return false;
            ResourceLocation id = biomeKey.location();
            String ns = id.getNamespace();
            String path = id.getPath();

            if ("alexscaves".equals(ns)) return true;
            return path.contains("cave") || path.contains("underground") || path.contains("subterranean");
        } catch (Exception ignored) {
            return false;
        }
    }
}
