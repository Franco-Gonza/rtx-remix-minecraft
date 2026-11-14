/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.particle;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.forge.mixin.ParticleAccessor;
import com.nvidia.rtxremix.forge.mixin.ParticleEngineAccessor;
import com.nvidia.rtxremix.forge.mixin.SingleQuadParticleAccessor;
import com.nvidia.rtxremix.forge.mixin.TextureSheetParticleAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * High-performance RTX Remix particle renderer for Minecraft Forge 1.20.1.
 * Captures active quad particles, generates world-space camera-facing billboards,
 * and renders them through RTX Remix with radiance cache lighting and draw-call alpha blending.
 */
public final class RemixParticleRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/ParticleRenderer");

    private static final int MAX_PARTICLES_PER_BATCH = 4096;
    private static final int VERTS_PER_PARTICLE = 4;
    private static final int INDICES_PER_PARTICLE = 6;
    private static final int STRIDE = HardcodedVertex.STRIDE; // 64 bytes

    private static final float[] IDENTITY_TRANSFORM = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f
    };

    private static final java.util.concurrent.atomic.AtomicLong MESH_HASH_COUNTER =
            new java.util.concurrent.atomic.AtomicLong(0x5041_5254_0000_0000L);

    private final Minecraft client;
    private int lastAtlasTexId = -1;
    private long particleAtlasTexture = 0;
    private long particleMaterial = 0;
    private long particleBlendExtPtr = 0;
    private long terrainBlendExtPtr = 0;

    // Double-buffered frame meshes to clean up after submit
    private final List<Long> activeMeshes = new ArrayList<>();
    private final List<Long> retiredMeshes = new ArrayList<>();

    private ByteBuffer vertexBuffer;
    private ByteBuffer indexBuffer;

    public RemixParticleRenderer(Minecraft client) {
        this.client = client;
    }

    public void initialize(RemixAPI api) {
        if (api == null) return;
        ensureMaterials(api);
    }

    private void ensureMaterials(RemixAPI api) {
        if (api == null || client == null) return;
        try {
            if (client.getTextureManager() == null) return;
            AbstractTexture tex = client.getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
            if (!(tex instanceof TextureAtlas atlas)) return;

            int texId = atlas.getId();
            if (texId <= 0) return;

            if (particleMaterial != 0 && lastAtlasTexId == texId) return;

            if (particleMaterial != 0) {
                try { api.destroyMaterial(particleMaterial); } catch (Exception ignored) {}
                particleMaterial = 0;
            }
            if (particleAtlasTexture != 0) {
                try { api.destroyTexture(particleAtlasTexture); } catch (Exception ignored) {}
                particleAtlasTexture = 0;
            }
            lastAtlasTexId = -1;

            lastAtlasTexId = texId;

            int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            try {
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

                if (width > 0 && height > 0) {
                    ByteBuffer buf = MemoryUtil.memAlloc(width * height * 4);
                    try {
                        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                        long hash = 0x5041_5254_5F41_544CL; // "PART_ATL"
                        TextureFromMemoryInfo texInfo = TextureFromMemoryInfo.builder()
                                .hash(hash)
                                .size(width, height)
                                .format(TextureFormat.RGBA8_UNORM)
                                .pixelData(buf)
                                .build();
                        particleAtlasTexture = api.createTextureFromMemory(texInfo);
                        LOGGER.info("Captured and uploaded particle atlas texture ({}x{}) to RTX Remix: handle=0x{}",
                                width, height, Long.toHexString(particleAtlasTexture));

                        MaterialInfoOpaqueEXT opaqueExt = MaterialInfoOpaqueEXT.builder()
                                .albedoConstant(1.0f, 1.0f, 1.0f)
                                .roughnessConstant(1.0f)
                                .metallicConstant(0.0f)
                                .useDrawCallAlphaState(true)
                                .build();

                        MaterialInfo matInfo = MaterialInfo.builder()
                                .hash(0x4D41_545F_5041_5254L) // "MAT_PART"
                                .albedoTexture(particleAtlasTexture)
                                .filterMode(0) // NEAREST
                                .opaqueExt(opaqueExt)
                                .build();
                        particleMaterial = api.createMaterial(matInfo);
                        LOGGER.info("Created RTX Remix particle material: handle=0x{}", Long.toHexString(particleMaterial));

                        if (particleBlendExtPtr == 0) {
                            InstanceInfoBlendEXT pBlend = InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(10);
                            particleBlendExtPtr = pBlend.allocNative(0);
                        }
                        if (terrainBlendExtPtr == 0) {
                            InstanceInfoBlendEXT tBlend = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(128);
                            terrainBlendExtPtr = tBlend.allocNative(0);
                        }
                    } finally {
                        MemoryUtil.memFree(buf);
                    }
                }
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            }
        } catch (Exception e) {
            LOGGER.error("Failed initializing particle materials in RTX Remix: {}", e.getMessage(), e);
        }
    }

    public void render(RemixAPI api, Camera camera, float partialTicks, long terrainCutoutMaterial) {
        if (api == null || client == null || client.particleEngine == null) return;
        ensureMaterials(api);
        if (particleMaterial == 0) return;

        // Clean up retired meshes from previous frame
        cleanupRetiredMeshes(api);

        ParticleEngineAccessor engineAccessor = (ParticleEngineAccessor) client.particleEngine;
        Map<ParticleRenderType, Queue<Particle>> particleMap = engineAccessor.rtxremix$getParticles();
        if (particleMap == null || particleMap.isEmpty()) return;

        Quaternionf camRot = camera.rotation();
        ParticleBillboardBasis basis = ParticleBillboardBasis.fromCamera(camRot.x, camRot.y, camRot.z, camRot.w);

        ensureBuffers();

        for (Map.Entry<ParticleRenderType, Queue<Particle>> entry : particleMap.entrySet()) {
            ParticleRenderType sheet = entry.getKey();
            Queue<Particle> queue = entry.getValue();
            if (queue == null || queue.isEmpty()) continue;
            if (sheet == ParticleRenderType.NO_RENDER || sheet == ParticleRenderType.CUSTOM) continue;

            boolean isTerrain = (sheet == ParticleRenderType.TERRAIN_SHEET);
            long matHandle = isTerrain
                    ? (terrainCutoutMaterial != 0 ? terrainCutoutMaterial : particleMaterial)
                    : particleMaterial;
            long pNext = isTerrain ? terrainBlendExtPtr : particleBlendExtPtr;
            int categoryFlags = isTerrain ? 0 : InstanceCategoryBit.PARTICLE;

            renderParticleQueue(api, queue, basis, partialTicks, matHandle, pNext, categoryFlags);
        }
    }

    private void renderParticleQueue(RemixAPI api, Queue<Particle> queue,
                                     ParticleBillboardBasis basis, float partialTicks,
                                     long materialHandle, long pNext, int categoryFlags) {
        int count = 0;
        vertexBuffer.clear();
        indexBuffer.clear();

        Particle[] particles;
        try {
            particles = queue.toArray(new Particle[0]);
        } catch (Throwable t) {
            return;
        }

        for (Particle p : particles) {
            if (p == null) continue;
            if (!(p instanceof SingleQuadParticle sqp)) continue;
            ParticleAccessor pa = (ParticleAccessor) p;
            if (pa.rtxremix$getRemoved()) continue;

            SingleQuadParticleAccessor sqpa = (SingleQuadParticleAccessor) sqp;

            int boundsMin = 0;
            int boundsMax = 0;
            if (p instanceof TextureSheetParticle tsp) {
                TextureAtlasSprite sprite = ((TextureSheetParticleAccessor) tsp).rtxremix$getSprite();
                if (sprite != null) {
                    float sMinU = sprite.getU0(), sMinV = sprite.getV0();
                    float sMaxU = sprite.getU1(), sMaxV = sprite.getV1();
                    boundsMin = HardcodedVertex.packHalf2x16(Math.min(sMinU, sMaxU), Math.min(sMinV, sMaxV));
                    boundsMax = HardcodedVertex.packHalf2x16(Math.max(sMinU, sMaxU), Math.max(sMinV, sMaxV));
                }
            }

            float u0 = sqpa.rtxremix$getU0();
            float v0 = sqpa.rtxremix$getV0();
            float u1 = sqpa.rtxremix$getU1();
            float v1 = sqpa.rtxremix$getV1();

            int color = ParticleTint.isSoot(p)
                    ? packColor(ParticleTint.SOOT_GREY, ParticleTint.SOOT_GREY, ParticleTint.SOOT_GREY, pa.rtxremix$getAlpha())
                    : packColor(pa.rtxremix$getRCol(), pa.rtxremix$getGCol(), pa.rtxremix$getBCol(), pa.rtxremix$getAlpha());

            writeParticleQuad(vertexBuffer, count, pa, sqpa, partialTicks, basis, u0, v0, u1, v1, color, boundsMin, boundsMax);

            int baseVert = count * VERTS_PER_PARTICLE;
            int baseIdx = count * INDICES_PER_PARTICLE;
            indexBuffer.putInt(baseIdx * Integer.BYTES, baseVert);
            indexBuffer.putInt((baseIdx + 1) * Integer.BYTES, baseVert + 1);
            indexBuffer.putInt((baseIdx + 2) * Integer.BYTES, baseVert + 2);
            indexBuffer.putInt((baseIdx + 3) * Integer.BYTES, baseVert);
            indexBuffer.putInt((baseIdx + 4) * Integer.BYTES, baseVert + 2);
            indexBuffer.putInt((baseIdx + 5) * Integer.BYTES, baseVert + 3);

            count++;
            if (count >= MAX_PARTICLES_PER_BATCH) {
                flushBatch(api, count, materialHandle, pNext, categoryFlags);
                count = 0;
                vertexBuffer.clear();
                indexBuffer.clear();
            }
        }

        if (count > 0) {
            flushBatch(api, count, materialHandle, pNext, categoryFlags);
        }
    }

    private void flushBatch(RemixAPI api, int count, long materialHandle, long pNext, int categoryFlags) {
        try {
            vertexBuffer.position(0);
            indexBuffer.position(0);

            MeshInfoSurfaceTriangles surf = new MeshInfoSurfaceTriangles();
            surf.vertexBuffer = vertexBuffer;
            surf.vertexCount = count * VERTS_PER_PARTICLE;
            surf.indexBuffer = indexBuffer;
            surf.indexCount = count * INDICES_PER_PARTICLE;
            surf.material = materialHandle;
            surf.flags = MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS;

            long meshHash = MESH_HASH_COUNTER.incrementAndGet();
            MeshInfo mi = MeshInfo.builder()
                    .hash(meshHash)
                    .surfaces(surf)
                    .build();

            long mHandle = api.createMesh(mi);
            if (mHandle != 0) {
                activeMeshes.add(mHandle);
                api.drawInstance(mHandle, IDENTITY_TRANSFORM, categoryFlags, 1, pNext);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed drawing particle batch: {}", e.getMessage());
        }
    }

    private static void writeParticleQuad(ByteBuffer vertices, int slot,
                                          ParticleAccessor pa, SingleQuadParticleAccessor sqpa,
                                          float tickDelta, ParticleBillboardBasis basis,
                                          float u0, float v0, float u1, float v1, int color,
                                          int boundsMin, int boundsMax) {
        double px = Mth.lerp(tickDelta, pa.rtxremix$getXo(), pa.rtxremix$getX());
        double py = Mth.lerp(tickDelta, pa.rtxremix$getYo(), pa.rtxremix$getY());
        double pz = Mth.lerp(tickDelta, pa.rtxremix$getZo(), pa.rtxremix$getZ());

        float angle = Mth.lerp(tickDelta, pa.rtxremix$getORoll(), pa.rtxremix$getRoll());
        float scale = sqpa.rtxremix$getQuadSize();
        float cosA = Mth.cos(angle);
        float sinA = Mth.sin(angle);

        float s00 = (cosA * basis.r00 + sinA * basis.r10) * scale;
        float s01 = (cosA * basis.r01 + sinA * basis.r11) * scale;
        float s10 = (-sinA * basis.r00 + cosA * basis.r10) * scale;
        float s11 = (-sinA * basis.r01 + cosA * basis.r11) * scale;
        float z00 = basis.r20 * scale;
        float z01 = basis.r21 * scale;

        int base = slot * VERTS_PER_PARTICLE * STRIDE;
        putActiveVertex(vertices, base,              px - s00 - s01, py - s10 - s11, pz - z00 - z01, basis.nx, basis.ny, basis.nz, u0, v1, color, boundsMin, boundsMax);
        putActiveVertex(vertices, base + STRIDE,     px + s00 - s01, py + s10 - s11, pz + z00 - z01, basis.nx, basis.ny, basis.nz, u1, v1, color, boundsMin, boundsMax);
        putActiveVertex(vertices, base + STRIDE * 2, px + s00 + s01, py + s10 + s11, pz + z00 + z01, basis.nx, basis.ny, basis.nz, u1, v0, color, boundsMin, boundsMax);
        putActiveVertex(vertices, base + STRIDE * 3, px - s00 + s01, py - s10 + s11, pz - z00 + z01, basis.nx, basis.ny, basis.nz, u0, v0, color, boundsMin, boundsMax);
    }

    private static void putActiveVertex(ByteBuffer buffer, int offset,
                                        double px, double py, double pz,
                                        float nx, float ny, float nz,
                                        float u, float v, int color,
                                        int boundsMin, int boundsMax) {
        buffer.putFloat(offset, (float) px);
        buffer.putFloat(offset + 4, (float) py);
        buffer.putFloat(offset + 8, (float) pz);
        buffer.putFloat(offset + 12, nx);
        buffer.putFloat(offset + 16, ny);
        buffer.putFloat(offset + 20, nz);
        buffer.putFloat(offset + 24, u);
        buffer.putFloat(offset + 28, v);
        buffer.putInt(offset + 32, color);
        buffer.putInt(offset + 36, boundsMin);
        buffer.putInt(offset + 40, boundsMax);
        buffer.putInt(offset + 44, 0);
        buffer.putInt(offset + 48, 0);
        buffer.putInt(offset + 52, 0);
        buffer.putInt(offset + 56, 0);
        buffer.putInt(offset + 60, 0);
    }

    private static int packColor(float r, float g, float b, float a) {
        int ri = (int) (Mth.clamp(r, 0f, 1f) * 255f) & 0xFF;
        int gi = (int) (Mth.clamp(g, 0f, 1f) * 255f) & 0xFF;
        int bi = (int) (Mth.clamp(b, 0f, 1f) * 255f) & 0xFF;
        int ai = (int) (Mth.clamp(a, 0f, 1f) * 255f) & 0xFF;
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private void ensureBuffers() {
        if (vertexBuffer == null) {
            vertexBuffer = ByteBuffer.allocateDirect(MAX_PARTICLES_PER_BATCH * VERTS_PER_PARTICLE * STRIDE);
        }
        if (indexBuffer == null) {
            indexBuffer = ByteBuffer.allocateDirect(MAX_PARTICLES_PER_BATCH * INDICES_PER_PARTICLE * Integer.BYTES);
        }
    }

    private void cleanupRetiredMeshes(RemixAPI api) {
        if (!retiredMeshes.isEmpty()) {
            for (Long m : retiredMeshes) {
                if (m != null && m != 0) {
                    try { api.destroyMesh(m); } catch (Exception ignored) {}
                }
            }
            retiredMeshes.clear();
        }
        if (!activeMeshes.isEmpty()) {
            retiredMeshes.addAll(activeMeshes);
            activeMeshes.clear();
        }
    }

    public void destroy(RemixAPI api) {
        if (api != null) {
            cleanupRetiredMeshes(api);
            for (Long m : retiredMeshes) {
                if (m != null && m != 0) {
                    try { api.destroyMesh(m); } catch (Exception ignored) {}
                }
            }
            retiredMeshes.clear();

            if (particleMaterial != 0) {
                try { api.destroyMaterial(particleMaterial); } catch (Exception ignored) {}
                particleMaterial = 0;
            }
            if (particleAtlasTexture != 0) {
                try { api.destroyTexture(particleAtlasTexture); } catch (Exception ignored) {}
                particleAtlasTexture = 0;
            }
            lastAtlasTexId = -1;
            if (particleBlendExtPtr != 0) {
                RemixBridge.freeExt(particleBlendExtPtr);
                particleBlendExtPtr = 0;
            }
            if (terrainBlendExtPtr != 0) {
                RemixBridge.freeExt(terrainBlendExtPtr);
                terrainBlendExtPtr = 0;
            }
        }
    }

    private record ParticleBillboardBasis(
            float r00, float r01,
            float r10, float r11,
            float r20, float r21,
            float nx, float ny, float nz) {

        static ParticleBillboardBasis fromCamera(float qx, float qy, float qz, float qw) {
            float xx = qx * qx, yy = qy * qy, zz = qz * qz;
            float xy = qx * qy, xz = qx * qz, yz = qy * qz;
            float wx = qw * qx, wy = qw * qy, wz = qw * qz;

            float r00 = 1f - 2f * (yy + zz);
            float r01 = 2f * (xy - wz);
            float r02 = 2f * (xz + wy);
            float r10 = 2f * (xy + wz);
            float r11 = 1f - 2f * (xx + zz);
            float r12 = 2f * (yz - wx);
            float r20 = 2f * (xz - wy);
            float r21 = 2f * (yz + wx);
            float r22 = 1f - 2f * (xx + yy);

            return new ParticleBillboardBasis(r00, r01, r10, r11, r20, r21, -r02, -r12, -r22);
        }
    }
}
