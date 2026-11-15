/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoDistantEXT;
import com.nvidia.rtxremix.LightInfoDomeEXT;
import com.nvidia.rtxremix.LightInfoRectEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.forge.util.DirectBufferPool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Manages atmospheric sky rendering, physically based dome lights, sky ambient lighting,
 * and celestial emissive meshes for the Sun and Moon phases.
 */
public class SkyRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/SkyRenderer");

    private static final float[] SKYLESS_SKY_RADIANCE = new float[]{0.0f, 0.0f, 0.0f};
    private static final float[] END_SKY_RADIANCE = new float[]{0.015f, 0.005f, 0.025f};

    private static final float SKY_AMBIENT_RECT_Y = 325.0f;
    private static final float SKY_AMBIENT_RECT_HALF_SIZE = 8000.0f;

    private static final ResourceLocation SUN_TEXTURE = new ResourceLocation("minecraft", "textures/environment/sun.png");
    private static final ResourceLocation MOON_PHASES_TEXTURE = new ResourceLocation("minecraft", "textures/environment/moon_phases.png");

    private static final float CELESTIAL_DISTANCE = 100.0f;
    private static final float SUN_HALF_SIZE = 30.0f;
    private static final float MOON_HALF_SIZE = 20.0f;
    private static final float SUN_RADIANCE = 8.0f;
    private static final float MOON_RADIANCE = 0.2f;
    private static final float CELESTIAL_HORIZON_FADE_MIN_HEIGHT = -(float) Math.sin(Math.toRadians(4.0));

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

    // Celestial assets
    private long sunTextureHandle = 0;
    private long moonTextureHandle = 0;
    private long sunMaterialHandle = 0;
    private long moonMaterialHandle = 0;
    private long sunMeshHandle = 0;
    private final long[] moonMeshHandles = new long[8];
    private long celestialBlendPNext = 0;
    private boolean celestialAssetsInitialized = false;

    private final float[] sunTransformMatrix = new float[12];
    private final float[] moonTransformMatrix = new float[12];
    private boolean drawSunThisFrame = false;
    private boolean drawMoonThisFrame = false;
    private int currentMoonPhase = 0;

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
        drawSunThisFrame = false;
        drawMoonThisFrame = false;

        if (!enabled || api == null || world == null) return;

        DimensionSpecialEffects effects = world.effects();
        DimensionSpecialEffects.SkyType skyType = effects != null ? effects.skyType() : DimensionSpecialEffects.SkyType.NORMAL;

        // Subterranean check: suppress atmospheric sky dome only when deep underground without skylight
        if (cameraPos != null) {
            BlockPos camBlockPos = BlockPos.containing(cameraPos);
            boolean noSkyAccess = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, camBlockPos) == 0;
            if ((cameraPos.y < 55 && noSkyAccess) || isSubterraneanBiome(world, cameraPos)) {
                renderFixedSky(api, SKYLESS_SKY_RADIANCE);
                return;
            }
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

            ensureCelestialAssets(api);
            updateCelestialBodies(world, cameraPos, tickDelta);
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
            dirX = -nx;
            dirY = -ny;
            dirZ = -nz;
            float lum = 32.0f * Math.max(0.05f, ny);
            float opticalDepth = 1.0f / Math.max(0.05f, ny);
            radR = lum * (float) Math.exp(-0.06f * opticalDepth);
            radG = lum * (float) Math.exp(-0.12f * opticalDepth);
            radB = lum * (float) Math.exp(-0.25f * opticalDepth);
        } else {
            dirX = nx;
            dirY = ny;
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
            long handle = api.createLight(lightInfo);
            if (handle != 0) {
                sunLightHandle = handle;
            }
        } catch (Exception e) {
            LOGGER.warn("Sun light update failed: {}", e.getMessage());
        }
    }

    private void updateCelestialBodies(ClientLevel world, Vec3 cameraPos, float tickDelta) {
        if (cameraPos == null) return;

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

        float[] sunDir = new float[]{nx, ny, nz};
        float[] moonDir = new float[]{-nx, -ny, -nz};

        float weatherVisibility = 1.0f - world.getRainLevel(tickDelta);
        float sunOpacity = smoothstep(CELESTIAL_HORIZON_FADE_MIN_HEIGHT, 0.0f, sunDir[1]) * weatherVisibility;
        float moonOpacity = smoothstep(CELESTIAL_HORIZON_FADE_MIN_HEIGHT, 0.0f, moonDir[1]) * weatherVisibility;

        if (sunOpacity > 0.01f && sunMeshHandle != 0) {
            updateCelestialTransform(sunTransformMatrix, cameraPos, sunDir, SUN_HALF_SIZE);
            drawSunThisFrame = true;
        }

        if (moonOpacity > 0.01f) {
            currentMoonPhase = world.getMoonPhase() & 7;
            if (moonMeshHandles[currentMoonPhase] != 0) {
                updateCelestialTransform(moonTransformMatrix, cameraPos, moonDir, MOON_HALF_SIZE);
                drawMoonThisFrame = true;
            }
        }
    }

    private static void updateCelestialTransform(float[] matrix, Vec3 cameraPos, float[] direction, float halfSize) {
        float dx = direction[0];
        float dy = direction[1];
        float dz = direction[2];
        float invLen = 1.0f / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx *= invLen;
        dy *= invLen;
        dz *= invLen;

        float rx = dz;
        float ry = 0.0f;
        float rz = -dx;
        float invRight = 1.0f / (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx *= invRight;
        rz *= invRight;

        float ux = dy * rz - dz * ry;
        float uy = dz * rx - dx * rz;
        float uz = dx * ry - dy * rx;

        matrix[0] = rx * halfSize;
        matrix[1] = ux * halfSize;
        matrix[2] = dx;
        matrix[3] = (float) cameraPos.x + dx * CELESTIAL_DISTANCE;

        matrix[4] = ry * halfSize;
        matrix[5] = uy * halfSize;
        matrix[6] = dy;
        matrix[7] = (float) cameraPos.y + dy * CELESTIAL_DISTANCE;

        matrix[8] = rz * halfSize;
        matrix[9] = uz * halfSize;
        matrix[10] = dz;
        matrix[11] = (float) cameraPos.z + dz * CELESTIAL_DISTANCE;
    }

    private void ensureCelestialAssets(RemixAPI api) {
        if (celestialAssetsInitialized) return;
        celestialAssetsInitialized = true;

        try {
            sunTextureHandle = loadTexture(api, SUN_TEXTURE, 0x53554E5445580001L);
            moonTextureHandle = loadTexture(api, MOON_PHASES_TEXTURE, 0x4D4F4F4E54455801L);

            if (sunTextureHandle != 0) {
                MaterialInfoOpaqueEXT sunOpaque = MaterialInfoOpaqueEXT.builder()
                        .albedoConstant(1.0f, 1.0f, 1.0f)
                        .opacityConstant(1.0f)
                        .roughnessConstant(1.0f)
                        .metallicConstant(0.0f)
                        .useDrawCallAlphaState(true)
                        .build();
                MaterialInfo sunMat = MaterialInfo.builder()
                        .albedoTexture(sunTextureHandle)
                        .emissiveTexture(sunTextureHandle)
                        .emissiveIntensity(SUN_RADIANCE)
                        .filterMode(0)
                        .wrapMode(MaterialInfo.WRAP_MODE_CLAMP, MaterialInfo.WRAP_MODE_CLAMP)
                        .opaqueExt(sunOpaque)
                        .build();
                sunMaterialHandle = api.createMaterial(sunMat);
                sunMeshHandle = createCelestialQuadMesh(api, sunMaterialHandle, 0.0f, 0.0f, 1.0f, 1.0f, 0x53554E5F4D455348L);
            }

            if (moonTextureHandle != 0) {
                MaterialInfoOpaqueEXT moonOpaque = MaterialInfoOpaqueEXT.builder()
                        .albedoConstant(1.0f, 1.0f, 1.0f)
                        .opacityConstant(1.0f)
                        .roughnessConstant(1.0f)
                        .metallicConstant(0.0f)
                        .useDrawCallAlphaState(true)
                        .build();
                MaterialInfo moonMat = MaterialInfo.builder()
                        .albedoTexture(moonTextureHandle)
                        .emissiveTexture(moonTextureHandle)
                        .emissiveIntensity(MOON_RADIANCE)
                        .filterMode(0)
                        .wrapMode(MaterialInfo.WRAP_MODE_CLAMP, MaterialInfo.WRAP_MODE_CLAMP)
                        .opaqueExt(moonOpaque)
                        .build();
                moonMaterialHandle = api.createMaterial(moonMat);

                for (int phase = 0; phase < 8; phase++) {
                    int col = phase % 4;
                    int row = phase / 4;
                    float minU = col / 4.0f;
                    float minV = row / 2.0f;
                    float maxU = (col + 1) / 4.0f;
                    float maxV = (row + 1) / 2.0f;
                    moonMeshHandles[phase] = createCelestialQuadMesh(
                            api, moonMaterialHandle, minU, minV, maxU, maxV, 0x4D4F4F4E00000000L | phase);
                }
            }

            InstanceInfoBlendEXT blend = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(1);
            blend.alphaBlendEnabled = 1;
            blend.srcColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
            blend.dstColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
            blend.srcAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ZERO;
            blend.dstAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
            celestialBlendPNext = blend.allocNative(0);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize celestial assets in Remix: {}", e.getMessage(), e);
        }
    }

    private long loadTexture(RemixAPI api, ResourceLocation id, long hash) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return 0;
        Optional<Resource> res = client.getResourceManager().getResource(id);
        if (res.isEmpty()) {
            LOGGER.warn("Missing celestial texture: {}", id);
            return 0;
        }

        try (InputStream is = res.get().open();
             NativeImage img = NativeImage.read(is)) {
            int w = img.getWidth();
            int h = img.getHeight();
            int sizeBytes = w * h * 4;
            ByteBuffer buf = MemoryUtil.memAlloc(sizeBytes);
            try {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int abgr = img.getPixelRGBA(x, y);
                        buf.put((byte) (abgr & 0xFF));
                        buf.put((byte) ((abgr >> 8) & 0xFF));
                        buf.put((byte) ((abgr >> 16) & 0xFF));
                        buf.put((byte) ((abgr >> 24) & 0xFF));
                    }
                }
                buf.flip();
                TextureFromMemoryInfo info = TextureFromMemoryInfo.builder()
                        .hash(hash)
                        .size(w, h)
                        .format(TextureFormat.RGBA8_UNORM)
                        .pixelData(buf)
                        .build();
                return api.createTextureFromMemory(info);
            } finally {
                MemoryUtil.memFree(buf);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load celestial texture {}: {}", id, e.getMessage(), e);
            return 0;
        }
    }

    private long createCelestialQuadMesh(RemixAPI api, long materialHandle,
                                         float minU, float minV, float maxU, float maxV,
                                         long hash) {
        int vertexBufferSize = 4 * HardcodedVertex.STRIDE;
        int indexBufferSize = 6 * Integer.BYTES;

        ByteBuffer vertexBuffer = DirectBufferPool.acquire(vertexBufferSize);
        ByteBuffer indexBuffer = DirectBufferPool.acquire(indexBufferSize);

        try {
            // 4 vertices of quad in local space (-1..1)
            putVertex(vertexBuffer, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f, -1.0f, minU, maxV);
            putVertex(vertexBuffer,  1.0f, -1.0f, 0.0f, 0.0f, 0.0f, -1.0f, maxU, maxV);
            putVertex(vertexBuffer,  1.0f,  1.0f, 0.0f, 0.0f, 0.0f, -1.0f, maxU, minV);
            putVertex(vertexBuffer, -1.0f,  1.0f, 0.0f, 0.0f, 0.0f, -1.0f, minU, minV);
            vertexBuffer.flip();

            indexBuffer.putInt(0).putInt(1).putInt(2);
            indexBuffer.putInt(0).putInt(2).putInt(3);
            indexBuffer.flip();

            MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
            surface.vertexBuffer = vertexBuffer;
            surface.vertexCount = 4;
            surface.indexBuffer = indexBuffer;
            surface.indexCount = 6;
            surface.material = materialHandle;

            MeshInfo meshInfo = MeshInfo.builder()
                    .hash(hash)
                    .surfaces(surface)
                    .build();
            return api.createMesh(meshInfo);
        } catch (Exception e) {
            LOGGER.error("Failed to create celestial quad mesh: {}", e.getMessage(), e);
            return 0;
        } finally {
            DirectBufferPool.release(vertexBuffer);
            DirectBufferPool.release(indexBuffer);
        }
    }

    private static void putVertex(ByteBuffer buf, float x, float y, float z,
                                  float nx, float ny, float nz,
                                  float u, float v) {
        buf.putFloat(x).putFloat(y).putFloat(z);
        buf.putFloat(nx).putFloat(ny).putFloat(nz);
        buf.putFloat(u).putFloat(v);
        buf.putInt(0xFFFFFFFF); // color
        buf.putInt(0); // texcoordBoundsMin
        buf.putInt(0); // texcoordBoundsMax
        buf.putInt(0); // _pad0
        buf.putInt(0); // _pad1
        buf.putInt(0); // _pad2
        buf.putInt(0); // _pad3
        buf.putInt(0); // _pad4
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private void renderFixedSky(RemixAPI api, float[] radiance) {
        if (sunLightHandle != 0) {
            try { api.destroyLight(sunLightHandle); } catch (Exception ignored) {}
            sunLightHandle = 0;
        }
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
        }
    }

    private void publishSkyDome(RemixAPI api, ByteBuffer textureData, float domeIntensity) {
        try {
            if (skyTextureHandle == 0) {
                TextureFromMemoryInfo textureInfo = TextureFromMemoryInfo.builder()
                        .hash(0x534B_595F_444F_4D45L) // "SKY_DOME"
                        .size(AtmosphericSkyGenerator.WIDTH, AtmosphericSkyGenerator.HEIGHT)
                        .format(TextureFormat.RGBA16_SFLOAT)
                        .pixelData(textureData)
                        .build();
                skyTextureHandle = api.createTextureFromMemory(textureInfo);

                LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
                domeExt.setColorTexture(skyTextureHandle);
                LightInfo lightInfo = LightInfo.builder()
                        .hash(0x534B_595F_4C49_4748L) // "SKY_LIGH"
                        .radiance(domeIntensity, domeIntensity, domeIntensity)
                        .domeExt(domeExt)
                        .build();
                skyDomeLightHandle = api.createLight(lightInfo);
            } else {
                try {
                    atmosphericTexturePatch.pixelData = textureData;
                    atmosphericTextureEntry.handle = skyTextureHandle;
                    api.updateTextureFromMemory(atmosphericTextureUpdate);
                } catch (Exception e) {
                    LOGGER.debug("Sky dome texture update skipped: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Sky dome publish failed: {}", e.getMessage());
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
                    .volumetricRadianceScale(0.0f)
                    .build();
            LightInfo lightInfo = LightInfo.builder()
                    .hash(0x534B_595F_414D_4249L) // "SKY_AMBI"
                    .radiance(r, g, b)
                    .rectExt(rectExt)
                    .build();
            long handle = api.createLight(lightInfo);
            if (handle != 0) {
                skyAmbientRectHandle = handle;
            }
        } catch (Exception e) {
            LOGGER.warn("Sky ambient rect draw failed: {}", e.getMessage());
        }
    }

    public void drawLights(RemixAPI api) {
        if (!enabled || api == null) return;
        if (skyDomeLightHandle != 0) {
            try { api.drawLightInstance(skyDomeLightHandle); } catch (Exception ignored) {}
        }
        if (sunLightHandle != 0) {
            try { api.drawLightInstance(sunLightHandle); } catch (Exception ignored) {}
        }
        if (skyAmbientRectHandle != 0) {
            try { api.drawLightInstance(skyAmbientRectHandle); } catch (Exception ignored) {}
        }

        if (drawSunThisFrame && sunMeshHandle != 0) {
            try {
                api.drawInstance(sunMeshHandle, sunTransformMatrix, InstanceCategoryBit.SKY, 1, celestialBlendPNext);
            } catch (Exception ignored) {}
        }

        if (drawMoonThisFrame && moonMeshHandles[currentMoonPhase] != 0) {
            try {
                api.drawInstance(moonMeshHandles[currentMoonPhase], moonTransformMatrix, InstanceCategoryBit.SKY, 1, celestialBlendPNext);
            } catch (Exception ignored) {}
        }
    }

    public void clearLevelState(RemixAPI api) {
        lastFixedR = -1.0f;
        lastFixedG = -1.0f;
        lastFixedB = -1.0f;
        drawSunThisFrame = false;
        drawMoonThisFrame = false;

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

        if (celestialBlendPNext != 0) {
            RemixBridge.freeExt(celestialBlendPNext);
            celestialBlendPNext = 0;
        }
        if (sunMeshHandle != 0) {
            try { api.destroyMesh(sunMeshHandle); } catch (Exception ignored) {}
            sunMeshHandle = 0;
        }
        for (int i = 0; i < moonMeshHandles.length; i++) {
            if (moonMeshHandles[i] != 0) {
                try { api.destroyMesh(moonMeshHandles[i]); } catch (Exception ignored) {}
                moonMeshHandles[i] = 0;
            }
        }
        if (sunMaterialHandle != 0) {
            try { api.destroyMaterial(sunMaterialHandle); } catch (Exception ignored) {}
            sunMaterialHandle = 0;
        }
        if (moonMaterialHandle != 0) {
            try { api.destroyMaterial(moonMaterialHandle); } catch (Exception ignored) {}
            moonMaterialHandle = 0;
        }
        if (sunTextureHandle != 0) {
            try { api.destroyTexture(sunTextureHandle); } catch (Exception ignored) {}
            sunTextureHandle = 0;
        }
        if (moonTextureHandle != 0) {
            try { api.destroyTexture(moonTextureHandle); } catch (Exception ignored) {}
            moonTextureHandle = 0;
        }
        celestialAssetsInitialized = false;
    }

    private static boolean isSubterraneanBiome(ClientLevel world, Vec3 cameraPos) {
        if (world == null || cameraPos == null) return false;
        try {
            BlockPos pos = BlockPos.containing(cameraPos);
            ResourceKey<?> biomeKey = world.getBiome(pos).unwrapKey().orElse(null);
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
