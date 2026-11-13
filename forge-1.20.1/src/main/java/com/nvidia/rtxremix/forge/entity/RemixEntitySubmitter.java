/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.forge.util.DirectBufferPool;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Submits captured entity geometry to the NVIDIA RTX Remix runtime.
 * Manages meshes, materials, textures, and retained scene instances.
 */
public class RemixEntitySubmitter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixEntitySubmitter.class);

    private final RemixAPI api;

    private record EntityPartKey(Entity entity, int layerIndex) {}
    private record MaterialKey(ResourceLocation textureId, RenderLayerUtils.SurfaceMode mode) {}

    private final Map<EntityPartKey, Long> retainedInstances = new HashMap<>();
    private final Map<Entity, List<EntityPartKey>> instancesByEntity = new HashMap<>();
    private final Set<Entity> seenEntitiesThisFrame = new HashSet<>();

    private final Map<MaterialKey, Long> materialCache = new HashMap<>();
    private final Map<ResourceLocation, Long> textureCache = new HashMap<>();
    private final List<Long> transientMeshes = new ArrayList<>();

    private long defaultWhiteTexture = 0;
    private int drawCount = 0;
    private int meshCreateCount = 0;
    private long frameIndex = 0;

    public RemixEntitySubmitter(RemixAPI api) {
        this.api = Objects.requireNonNull(api, "RemixAPI must not be null");
        initDefaultTextures();
    }

    private void initDefaultTextures() {
        ByteBuffer buf = MemoryUtil.memAlloc(4);
        try {
            buf.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
            buf.flip();
            TextureFromMemoryInfo info = TextureFromMemoryInfo.builder()
                    .hash(0xCAFE_BABE_0000_0001L)
                    .size(1, 1)
                    .format(TextureFormat.RGBA8_UNORM)
                    .pixelData(buf)
                    .build();
            defaultWhiteTexture = api.createTextureFromMemory(info);
        } catch (Exception e) {
            LOGGER.error("Failed to create default white texture for entity submitter", e);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    public void beginFrame() {
        seenEntitiesThisFrame.clear();
        drawCount = 0;
        meshCreateCount = 0;
        frameIndex++;

        // Clean up transient meshes from previous frames
        for (long mesh : transientMeshes) {
            try {
                api.destroyMesh(mesh);
            } catch (Exception ignored) {}
        }
        transientMeshes.clear();
    }

    public void submitEntity(EntityCaptureResult result, Vec3 renderOffset) {
        if (result == null || result.layers().isEmpty()) {
            return;
        }

        Entity entity = result.entity();
        seenEntitiesThisFrame.add(entity);

        Transform worldTransform = new Transform();
        worldTransform.setTranslation(
                (float) (result.worldPos().x + renderOffset.x),
                (float) (result.worldPos().y + renderOffset.y),
                (float) (result.worldPos().z + renderOffset.z)
        );

        for (RemixVertexConsumerProvider.CapturedLayer layer : result.layers()) {
            submitLayer(result, layer, worldTransform);
        }
    }

    private void submitLayer(EntityCaptureResult result,
                            RemixVertexConsumerProvider.CapturedLayer layer,
                            Transform worldTransform) {
        int vertexCount = layer.vertexCount();
        if (vertexCount <= 0) {
            return;
        }

        RemixCapturingConsumer consumer = layer.consumer();
        long materialHandle = getOrCreateMaterial(layer.textureId(), RenderLayerUtils.surfaceMode(layer.renderLayer()));

        int indexCount = computeIndexCount(layer.topology(), vertexCount);
        if (indexCount <= 0) {
            return;
        }

        int vertexBufferSize = vertexCount * HardcodedVertex.STRIDE;
        int indexBufferSize = indexCount * Integer.BYTES;

        ByteBuffer vertexBuffer = DirectBufferPool.acquire(vertexBufferSize);
        ByteBuffer indexBuffer = DirectBufferPool.acquire(indexBufferSize);

        long meshHandle = 0;
        try {
            float[] px = consumer.getPositionsX();
            float[] py = consumer.getPositionsY();
            float[] pz = consumer.getPositionsZ();
            float[] nx = consumer.getNormalsX();
            float[] ny = consumer.getNormalsY();
            float[] nz = consumer.getNormalsZ();
            float[] tu = consumer.getUvsU();
            float[] tv = consumer.getUvsV();
            int[] colors = consumer.getColors();

            for (int i = 0; i < vertexCount; i++) {
                vertexBuffer.putFloat(px[i]).putFloat(py[i]).putFloat(pz[i]);
                vertexBuffer.putFloat(nx[i]).putFloat(ny[i]).putFloat(nz[i]);
                vertexBuffer.putFloat(tu[i]).putFloat(tv[i]);
                vertexBuffer.putInt(colors[i]);
                vertexBuffer.putInt(0); // texcoordBoundsMin
                vertexBuffer.putInt(0); // texcoordBoundsMax
                vertexBuffer.putInt(0); // _pad0
                vertexBuffer.putInt(0); // _pad1
                vertexBuffer.putInt(0); // _pad2
                vertexBuffer.putInt(0); // _pad3
                vertexBuffer.putInt(0); // _pad4
            }
            vertexBuffer.flip();

            buildIndices(indexBuffer, layer.topology(), vertexCount);
            indexBuffer.flip();

            MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
            surface.vertexBuffer = vertexBuffer;
            surface.vertexCount = vertexCount;
            surface.indexBuffer = indexBuffer;
            surface.indexCount = indexCount;
            surface.material = materialHandle;

            long meshHash = computeMeshHash(px, py, pz, vertexCount, layer.layerIndex());
            MeshInfo meshInfo = MeshInfo.builder()
                    .hash(meshHash)
                    .surfaces(surface)
                    .build();

            meshHandle = api.createMesh(meshInfo);
            meshCreateCount++;
            if (layer.transientGeometry()) {
                transientMeshes.add(meshHandle);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to create mesh for entity layer {}", layer.textureId(), e);
            return;
        } finally {
            DirectBufferPool.release(vertexBuffer);
            DirectBufferPool.release(indexBuffer);
        }

        if (meshHandle == 0) {
            return;
        }

        EntityPartKey partKey = new EntityPartKey(result.entity(), layer.layerIndex());
        Long existingInstance = retainedInstances.get(partKey);
        int categoryFlags = result.firstPersonLocalPlayer()
                ? (InstanceCategoryBit.THIRD_PERSON_PLAYER_MODEL | InstanceCategoryBit.THIRD_PERSON_PLAYER_BODY)
                : 0;

        try {
            if (existingInstance != null) {
                api.sceneInstanceUpdate(existingInstance, meshHandle, worldTransform.matrix, categoryFlags, 0, 0);
            } else {
                long instanceHandle = api.sceneInstanceAdd(meshHandle, worldTransform.matrix, categoryFlags, 0, 0);
                retainedInstances.put(partKey, instanceHandle);
                instancesByEntity.computeIfAbsent(result.entity(), k -> new ArrayList<>()).add(partKey);
            }
            drawCount++;
        } catch (Exception e) {
            LOGGER.error("Failed to submit scene instance for entity {}", result.entity().getType(), e);
        }
    }

    private int computeIndexCount(RenderLayerUtils.Topology topology, int vertexCount) {
        if (topology == RenderLayerUtils.Topology.QUADS) {
            return (vertexCount / 4) * 6;
        } else if (topology == RenderLayerUtils.Topology.TRIANGLE_STRIP) {
            return Math.max(0, (vertexCount - 2) * 3);
        }
        return 0;
    }

    private void buildIndices(ByteBuffer indexBuffer, RenderLayerUtils.Topology topology, int vertexCount) {
        if (topology == RenderLayerUtils.Topology.QUADS) {
            int quads = vertexCount / 4;
            for (int q = 0; q < quads; q++) {
                int base = q * 4;
                indexBuffer.putInt(base);
                indexBuffer.putInt(base + 1);
                indexBuffer.putInt(base + 2);
                indexBuffer.putInt(base);
                indexBuffer.putInt(base + 2);
                indexBuffer.putInt(base + 3);
            }
        } else if (topology == RenderLayerUtils.Topology.TRIANGLE_STRIP) {
            for (int i = 0; i < vertexCount - 2; i++) {
                if ((i & 1) == 0) {
                    indexBuffer.putInt(i);
                    indexBuffer.putInt(i + 1);
                    indexBuffer.putInt(i + 2);
                } else {
                    indexBuffer.putInt(i + 1);
                    indexBuffer.putInt(i);
                    indexBuffer.putInt(i + 2);
                }
            }
        }
    }

    private long computeMeshHash(float[] px, float[] py, float[] pz, int count, int layerIndex) {
        long h = 1125899906842597L;
        h = 31 * h + layerIndex;
        int step = Math.max(1, count / 8);
        for (int i = 0; i < count; i += step) {
            h = 31 * h + Float.floatToIntBits(px[i]);
            h = 31 * h + Float.floatToIntBits(py[i]);
            h = 31 * h + Float.floatToIntBits(pz[i]);
        }
        return h;
    }

    private long getOrCreateMaterial(ResourceLocation textureId, RenderLayerUtils.SurfaceMode surfaceMode) {
        MaterialKey key = new MaterialKey(textureId, surfaceMode);
        Long cached = materialCache.get(key);
        if (cached != null) {
            return cached;
        }

        long textureHandle = getOrCreateTexture(textureId);
        MaterialInfo info = new MaterialInfo();
        info.albedoTexture = textureHandle;
        info.hash = Objects.hash(textureId, surfaceMode);
        info.filterMode = 0; // Nearest neighbor for pixel art textures

        if (surfaceMode == RenderLayerUtils.SurfaceMode.CUTOUT) {
            MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                    .albedoConstant(1.0f, 1.0f, 1.0f)
                    .roughnessConstant(0.8f)
                    .metallicConstant(0.0f)
                    .alphaTestType(4) // GREATER_OR_EQUAL
                    .alphaReferenceValue(128)
                    .useDrawCallAlphaState(true)
                    .build();
            info.opaqueExt = opaque;
        } else if (surfaceMode == RenderLayerUtils.SurfaceMode.EMISSIVE) {
            MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                    .albedoConstant(1.0f, 1.0f, 1.0f)
                    .roughnessConstant(0.8f)
                    .metallicConstant(0.0f)
                    .blendEnabled(true)
                    .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA_EMISSIVE)
                    .build();
            info.opaqueExt = opaque;
            info.emissiveTexture = textureHandle;
            info.emissiveIntensity = 25.0f;
        } else if (surfaceMode == RenderLayerUtils.SurfaceMode.TRANSLUCENT) {
            MaterialInfoTranslucentEXT trans = MaterialInfoTranslucentEXT.builder()
                    .refractiveIndex(1.0f)
                    .transmittanceColor(1.0f, 1.0f, 1.0f)
                    .transmittanceMeasurementDistance(1.0f)
                    .useDiffuseLayer(true)
                    .build();
            info.translucentExt = trans;
        } else {
            // SOLID / OPAQUE mobs (zombies, creepers, cows, player, armor, etc.)
            MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                    .albedoConstant(1.0f, 1.0f, 1.0f)
                    .roughnessConstant(0.8f)
                    .metallicConstant(0.0f)
                    .useDrawCallAlphaState(true)
                    .build();
            info.opaqueExt = opaque;
        }

        try {
            long matHandle = api.createMaterial(info);
            materialCache.put(key, matHandle);
            return matHandle;
        } catch (Exception e) {
            LOGGER.error("Failed to create material for {}", textureId, e);
            return 0;
        }
    }

    private long getOrCreateTexture(ResourceLocation textureId) {
        if (textureId == null || textureId.equals(RenderLayerUtils.UNTEXTURED_LAYER_ID)) {
            return defaultWhiteTexture;
        }

        Long cached = textureCache.get(textureId);
        if (cached != null) {
            return cached;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null) return defaultWhiteTexture;

        ResourceManager rm = client.getResourceManager();
        Optional<Resource> res = rm.getResource(textureId);
        if (res.isEmpty()) {
            textureCache.put(textureId, defaultWhiteTexture);
            return defaultWhiteTexture;
        }

        try (InputStream is = res.get().open();
             NativeImage image = NativeImage.read(is)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int sizeBytes = width * height * 4;

            ByteBuffer buf = MemoryUtil.memAlloc(sizeBytes);
            try {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int abgr = image.getPixelRGBA(x, y);
                        byte r = (byte) (abgr & 0xFF);
                        byte g = (byte) ((abgr >> 8) & 0xFF);
                        byte b = (byte) ((abgr >> 16) & 0xFF);
                        byte a = (byte) ((abgr >> 24) & 0xFF);
                        buf.put(r).put(g).put(b).put(a);
                    }
                }
                buf.flip();

                long texHash = ((long) textureId.hashCode() << 32) ^ ((long) width * 31L + height) ^ 0x55AA55AA55AA55AAL;
                if (texHash == 0) texHash = 1L;
                TextureFromMemoryInfo texInfo = TextureFromMemoryInfo.builder()
                        .hash(texHash)
                        .size(width, height)
                        .format(TextureFormat.RGBA8_UNORM)
                        .pixelData(buf)
                        .build();
                long handle = api.createTextureFromMemory(texInfo);
                textureCache.put(textureId, handle);
                return handle;
            } finally {
                MemoryUtil.memFree(buf);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load entity texture {}: fallback to default", textureId);
            textureCache.put(textureId, defaultWhiteTexture);
            return defaultWhiteTexture;
        }
    }

    public void endFrame() {
        Iterator<Map.Entry<Entity, List<EntityPartKey>>> it = instancesByEntity.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Entity, List<EntityPartKey>> entry = it.next();
            Entity entity = entry.getKey();
            if (!seenEntitiesThisFrame.contains(entity) || !entity.isAlive()) {
                for (EntityPartKey partKey : entry.getValue()) {
                    Long instance = retainedInstances.remove(partKey);
                    if (instance != null) {
                        try {
                            api.sceneInstanceRemove(instance);
                        } catch (Exception ignored) {}
                    }
                }
                it.remove();
            }
        }
    }

    public void destroy() {
        for (Long instance : retainedInstances.values()) {
            try {
                api.sceneInstanceRemove(instance);
            } catch (Exception ignored) {}
        }
        retainedInstances.clear();
        instancesByEntity.clear();

        for (Long mat : materialCache.values()) {
            try {
                api.destroyMaterial(mat);
            } catch (Exception ignored) {}
        }
        materialCache.clear();

        for (Long tex : textureCache.values()) {
            if (tex != defaultWhiteTexture) {
                try {
                    api.destroyTexture(tex);
                } catch (Exception ignored) {}
            }
        }
        textureCache.clear();

        if (defaultWhiteTexture != 0) {
            try {
                api.destroyTexture(defaultWhiteTexture);
            } catch (Exception ignored) {}
            defaultWhiteTexture = 0;
        }
    }

    public int getLastDrawCount() {
        return drawCount;
    }

    public int getLastMeshCreateCount() {
        return meshCreateCount;
    }
}
