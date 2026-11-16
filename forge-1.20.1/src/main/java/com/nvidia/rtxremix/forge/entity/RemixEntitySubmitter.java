/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.forge.util.CanonicalIndexBuffer;
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

    private record EntityPartKey(Object entity, int layerIndex) {}
    private record MaterialKey(ResourceLocation textureId, RenderLayerUtils.SurfaceMode mode) {}

    private final Map<EntityPartKey, Long> retainedInstances = new HashMap<>();
    private final Map<EntityPartKey, Long> instanceMeshes = new HashMap<>();
    private final Map<Object, List<EntityPartKey>> instancesByEntity = new HashMap<>();
    private final Set<Object> seenEntitiesThisFrame = new HashSet<>();

    private final Map<MaterialKey, Long> materialCache = new HashMap<>();
    private final Map<ResourceLocation, Long> textureCache = new HashMap<>();
    private final List<Long> transientMeshes = new ArrayList<>();

    private long defaultWhiteTexture = 0;
    private long defaultWhiteMaterial = 0;
    private int drawCount = 0;
    private int meshCreateCount = 0;
    private long frameIndex = 0;

    private static java.lang.reflect.Field httpTextureFileField = null;
    private static boolean httpTextureFileFieldFailed = false;

    private static java.io.File getHttpTextureFile(net.minecraft.client.renderer.texture.AbstractTexture tex) {
        if (tex != null && "net.minecraft.client.renderer.texture.HttpTexture".equals(tex.getClass().getName())) {
            if (!httpTextureFileFieldFailed && httpTextureFileField == null) {
                try {
                    httpTextureFileField = tex.getClass().getDeclaredField("file");
                    httpTextureFileField.setAccessible(true);
                } catch (Exception e) {
                    try {
                        httpTextureFileField = tex.getClass().getDeclaredField("f_118000_");
                        httpTextureFileField.setAccessible(true);
                    } catch (Exception e2) {
                        httpTextureFileFieldFailed = true;
                    }
                }
            }
            if (httpTextureFileField != null) {
                try {
                    return (java.io.File) httpTextureFileField.get(tex);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private long solidBlendPNext = 0;
    private long cutoutBlendPNext = 0;
    private long translucentBlendPNext = 0;

    private void ensureBlendExtensions() {
        if (solidBlendPNext == 0) {
            solidBlendPNext = InstanceInfoBlendEXT.textureTimesVertexColor().allocNative(0);
        }
        if (cutoutBlendPNext == 0) {
            cutoutBlendPNext = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(128).allocNative(0);
        }
        if (translucentBlendPNext == 0) {
            translucentBlendPNext = InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(1).allocNative(0);
        }
    }

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

            MaterialInfo whiteMatInfo = new MaterialInfo();
            whiteMatInfo.albedoTexture = defaultWhiteTexture;
            whiteMatInfo.hash = 0xCAFE_BABE_0000_0002L;
            MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                    .albedoConstant(1.0f, 1.0f, 1.0f)
                    .roughnessConstant(0.8f)
                    .metallicConstant(0.0f)
                    .useDrawCallAlphaState(false)
                    .build();
            whiteMatInfo.opaqueExt = opaque;
            defaultWhiteMaterial = api.createMaterial(whiteMatInfo);
        } catch (Exception e) {
            LOGGER.error("Failed to create default white texture/material for entity submitter", e);
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

    public int submitEntity(EntityCaptureResult result) {
        return submitEntity(result, Vec3.ZERO);
    }

    public int submitEntity(EntityCaptureResult result, Vec3 renderOffset) {
        if (result == null || result.layers().isEmpty()) {
            return 0;
        }

        Object entity = result.entity();
        seenEntitiesThisFrame.add(entity);

        Transform worldTransform = new Transform();
        worldTransform.setTranslation(
                (float) (result.worldPos().x + renderOffset.x),
                (float) (result.worldPos().y + renderOffset.y),
                (float) (result.worldPos().z + renderOffset.z)
        );

        int submittedCount = 0;
        for (RemixVertexConsumerProvider.CapturedLayer layer : result.layers()) {
            if (submitLayer(result, layer, worldTransform)) {
                submittedCount++;
            }
        }
        return submittedCount;
    }

    private boolean submitLayer(EntityCaptureResult result,
                                RemixVertexConsumerProvider.CapturedLayer layer,
                                Transform worldTransform) {
        int vertexCount = layer.vertexCount();
        if (vertexCount <= 0) {
            return false;
        }

        RemixCapturingConsumer consumer = layer.consumer();
        RenderLayerUtils.SurfaceMode surfaceMode = RenderLayerUtils.surfaceMode(layer.renderLayer());
        if (result.entity() instanceof net.minecraft.world.entity.player.Player || result.firstPersonLocalPlayer()) {
            if (surfaceMode == RenderLayerUtils.SurfaceMode.TRANSLUCENT || surfaceMode == RenderLayerUtils.SurfaceMode.GLASS) {
                surfaceMode = RenderLayerUtils.SurfaceMode.CUTOUT;
            }
        }
        long materialHandle = getOrCreateMaterial(layer.textureId(), surfaceMode);
        if (materialHandle == 0) {
            return false;
        }

        int indexCount = computeIndexCount(layer.topology(), vertexCount);
        if (indexCount <= 0) {
            return false;
        }

        int vertexBufferSize = vertexCount * HardcodedVertex.STRIDE;

        ByteBuffer vertexBuffer = DirectBufferPool.acquire(vertexBufferSize);
        boolean isCanonicalIndex = (layer.topology() == RenderLayerUtils.Topology.QUADS);
        ByteBuffer indexBuffer = isCanonicalIndex
                ? CanonicalIndexBuffer.INSTANCE.getBuffer(indexCount)
                : DirectBufferPool.acquire(indexCount * Integer.BYTES);

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

            long geomHash = 0xcbf29ce484222325L;
            for (int i = 0; i < vertexCount; i++) {
                float x = px[i];
                float y = py[i];
                float z = pz[i];
                vertexBuffer.putFloat(x).putFloat(y).putFloat(z);
                vertexBuffer.putFloat(nx[i]).putFloat(ny[i]).putFloat(nz[i]);
                vertexBuffer.putFloat(tu[i]).putFloat(tv[i]);
                int c = colors[i];
                vertexBuffer.putInt(c);
                vertexBuffer.putInt(0); // texcoordBoundsMin
                vertexBuffer.putInt(0); // texcoordBoundsMax
                vertexBuffer.putInt(0); // _pad0
                vertexBuffer.putInt(0); // _pad1
                vertexBuffer.putInt(0); // _pad2
                vertexBuffer.putInt(0); // _pad3
                vertexBuffer.putInt(0); // _pad4

                int bits = Float.floatToRawIntBits(x)
                        ^ (Float.floatToRawIntBits(y) * 31)
                        ^ (Float.floatToRawIntBits(z) * 1000003)
                        ^ c;
                geomHash = (geomHash ^ bits) * 1099511628211L;
            }
            vertexBuffer.flip();

            if (!isCanonicalIndex) {
                buildIndices(indexBuffer, layer.topology(), vertexCount);
                indexBuffer.flip();
            }

            MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
            surface.vertexBuffer = vertexBuffer;
            surface.vertexCount = vertexCount;
            surface.indexBuffer = indexBuffer;
            surface.indexCount = indexCount;
            surface.material = materialHandle;

            int entityId = (result.entity() instanceof Entity e)
                    ? e.getId()
                    : (result.entity() != null ? System.identityHashCode(result.entity()) : 0);

            long meshHash = ((long) entityId * 31L + layer.layerIndex()) * 31L + vertexCount;
            if (result.entityTypeKey() != null) {
                meshHash = meshHash * 31L + result.entityTypeKey().hashCode();
            }
            if (layer.textureId() != null) {
                meshHash = meshHash * 31L + layer.textureId().hashCode();
            }
            meshHash = meshHash * 31L + materialHandle;
            meshHash = meshHash * 31L + (layer.topology() != null ? layer.topology().ordinal() : 0);
            meshHash ^= geomHash;
            if (meshHash == 0) {
                meshHash = 1L;
            }

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
            return false;
        } finally {
            DirectBufferPool.release(vertexBuffer);
            if (!isCanonicalIndex) {
                DirectBufferPool.release(indexBuffer);
            }
        }

        if (meshHandle == 0) {
            return false;
        }

        ensureBlendExtensions();
        long blendPNext = (surfaceMode == RenderLayerUtils.SurfaceMode.CUTOUT)
                ? cutoutBlendPNext
                : (surfaceMode == RenderLayerUtils.SurfaceMode.TRANSLUCENT ? translucentBlendPNext : solidBlendPNext);

        EntityPartKey partKey = new EntityPartKey(result.entity(), layer.layerIndex());
        Long existingInstance = retainedInstances.get(partKey);
        int categoryFlags = result.firstPersonLocalPlayer()
                ? (InstanceCategoryBit.THIRD_PERSON_PLAYER_MODEL | InstanceCategoryBit.THIRD_PERSON_PLAYER_BODY)
                : 0;

        try {
            if (existingInstance != null) {
                api.sceneInstanceUpdate(existingInstance, meshHandle, worldTransform.matrix, categoryFlags, 1, blendPNext);
            } else {
                long instanceHandle = api.sceneInstanceAdd(meshHandle, worldTransform.matrix, categoryFlags, 1, blendPNext);
                retainedInstances.put(partKey, instanceHandle);
                instancesByEntity.computeIfAbsent(result.entity(), k -> new ArrayList<>()).add(partKey);
            }
            if (!layer.transientGeometry()) {
                Long oldMesh = instanceMeshes.put(partKey, meshHandle);
                if (oldMesh != null && oldMesh != 0 && oldMesh != meshHandle) {
                    try {
                        api.destroyMesh(oldMesh);
                    } catch (Exception ignored) {}
                }
            }
            drawCount++;
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to submit scene instance for entity {}", result.entityTypeKey(), e);
            return false;
        }
    }

    private int computeIndexCount(RenderLayerUtils.Topology topology, int vertexCount) {
        if (topology == RenderLayerUtils.Topology.QUADS) {
            return (vertexCount / 4) * 6;
        } else if (topology == RenderLayerUtils.Topology.TRIANGLE_STRIP) {
            return Math.max(0, (vertexCount - 2) * 3);
        } else if (topology == RenderLayerUtils.Topology.TRIANGLES) {
            return (vertexCount / 3) * 3;
        } else if (topology == RenderLayerUtils.Topology.TRIANGLE_FAN) {
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
                indexBuffer.putInt(base + 2);
                indexBuffer.putInt(base + 1);
                indexBuffer.putInt(base);
                indexBuffer.putInt(base + 3);
                indexBuffer.putInt(base + 2);
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
        } else if (topology == RenderLayerUtils.Topology.TRIANGLES) {
            int tris = (vertexCount / 3) * 3;
            for (int i = 0; i < tris; i++) {
                indexBuffer.putInt(i);
            }
        } else if (topology == RenderLayerUtils.Topology.TRIANGLE_FAN) {
            for (int i = 1; i < vertexCount - 1; i++) {
                indexBuffer.putInt(0);
                indexBuffer.putInt(i);
                indexBuffer.putInt(i + 1);
            }
        }
    }

    private long getOrCreateMaterial(ResourceLocation textureId, RenderLayerUtils.SurfaceMode surfaceMode) {
        MaterialKey key = new MaterialKey(textureId, surfaceMode);
        Long cached = materialCache.get(key);
        if (cached != null) {
            return cached;
        }

        long textureHandle = getOrCreateTexture(textureId);
        if (textureHandle == defaultWhiteTexture && !RenderLayerUtils.UNTEXTURED_LAYER_ID.equals(textureId)) {
            // Texture is not yet ready (e.g. async skin download in progress)
            // Return shared default white material without caching in materialCache so skin binds immediately once ready
            return defaultWhiteMaterial;
        }

        MaterialInfo info = new MaterialInfo();
        info.albedoTexture = textureHandle;
        info.hash = Objects.hash(textureId, surfaceMode);
        info.filterMode = 0; // Nearest neighbor for pixel art textures

        if (surfaceMode == RenderLayerUtils.SurfaceMode.CUTOUT) {
            MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                    .albedoConstant(1.0f, 1.0f, 1.0f)
                    .roughnessConstant(0.8f)
                    .metallicConstant(0.0f)
                    .alphaTestType(6) // GREATER_OR_EQUAL
                    .alphaReferenceValue(128)
                    .useDrawCallAlphaState(false)
                    .build();
            info.opaqueExt = opaque;
        } else if (surfaceMode == RenderLayerUtils.SurfaceMode.EMISSIVE) {
            MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                    .albedoConstant(1.0f, 1.0f, 1.0f)
                    .roughnessConstant(0.8f)
                    .metallicConstant(0.0f)
                    .blendEnabled(true)
                    .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA_EMISSIVE)
                    .useDrawCallAlphaState(false)
                    .build();
            info.opaqueExt = opaque;
            info.emissiveTexture = textureHandle;
            info.emissiveIntensity = 25.0f;
        } else if (surfaceMode == RenderLayerUtils.SurfaceMode.TRANSLUCENT) {
            MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                    .albedoConstant(1.0f, 1.0f, 1.0f)
                    .roughnessConstant(0.8f)
                    .metallicConstant(0.0f)
                    .blendEnabled(true)
                    .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA)
                    .useDrawCallAlphaState(false)
                    .build();
            info.opaqueExt = opaque;
        } else if (surfaceMode == RenderLayerUtils.SurfaceMode.GLASS) {
            MaterialInfoTranslucentEXT trans = MaterialInfoTranslucentEXT.builder()
                    .refractiveIndex(1.0f)
                    .transmittanceColor(1.0f, 1.0f, 1.0f)
                    .transmittanceMeasurementDistance(1.0f)
                    .useDiffuseLayer(false)
                    .thinWall(0.01f)
                    .build();
            info.translucentExt = trans;
        } else {
            // SOLID / OPAQUE mobs (zombies, creepers, cows, player, armor, etc.)
            MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                    .albedoConstant(1.0f, 1.0f, 1.0f)
                    .roughnessConstant(0.8f)
                    .metallicConstant(0.0f)
                    .useDrawCallAlphaState(false)
                    .build();
            info.opaqueExt = opaque;
        }

        try {
            long matHandle = api.createMaterial(info);
            materialCache.put(key, matHandle);
            return matHandle;
        } catch (Exception e) {
            LOGGER.error("Failed to create material for {}", textureId, e);
            return defaultWhiteMaterial;
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

        NativeImage image = null;
        boolean ownsImage = false;
        try {
            if (res.isPresent()) {
                image = NativeImage.read(res.get().open());
                ownsImage = true;
            } else {
                net.minecraft.client.renderer.texture.AbstractTexture tex =
                        client.getTextureManager().getTexture(textureId);
                if (tex instanceof net.minecraft.client.renderer.texture.DynamicTexture dt) {
                    image = dt.getPixels();
                    ownsImage = false;
                } else {
                    java.io.File skinFile = getHttpTextureFile(tex);
                    if (skinFile != null && skinFile.isFile() && skinFile.length() > 0) {
                        try (InputStream is = new java.io.FileInputStream(skinFile)) {
                            image = NativeImage.read(is);
                            ownsImage = true;
                        } catch (Exception ignored) {}
                    }
                    if (image == null && tex != null && tex.getId() > 0) {
                        // Extract GPU texture via OpenGL for TextureAtlas (chests, beds, shulkers, signs, banners), HttpTexture, etc.
                        int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
                        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, tex.getId());
                        try {
                            int w = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
                            int h = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
                            if (w > 0 && h > 0) {
                                ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
                                try {
                                    buf.clear();
                                    org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                                            org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, buf);
                                    buf.position(0);
                                    buf.limit(w * h * 4);
                                    long texHash = ((long) textureId.hashCode() << 32) ^ ((long) w * 31L + h) ^ 0x55AA55AA55AA55AAL;
                                    if (texHash == 0) texHash = 1L;
                                    TextureFromMemoryInfo texInfo = TextureFromMemoryInfo.builder()
                                            .hash(texHash)
                                            .size(w, h)
                                            .format(TextureFormat.RGBA8_UNORM)
                                            .pixelData(buf)
                                            .build();
                                    long handle = api.createTextureFromMemory(texInfo);
                                    textureCache.put(textureId, handle);
                                    return handle;
                                } finally {
                                    MemoryUtil.memFree(buf);
                                }
                            }
                        } finally {
                            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex);
                        }
                    }
                }
            }

            if (image == null) {
                // Do not cache permanently so async skin downloads can be captured when ready
                return defaultWhiteTexture;
            }

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
            return defaultWhiteTexture;
        } finally {
            if (ownsImage && image != null) {
                image.close();
            }
        }
    }

    public void endFrame() {
        Iterator<Map.Entry<Object, List<EntityPartKey>>> it = instancesByEntity.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, List<EntityPartKey>> entry = it.next();
            Object entity = entry.getKey();
            boolean alive = true;
            if (entity instanceof Entity e) {
                alive = e.isAlive();
            } else if (entity instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                alive = !be.isRemoved();
            }
            if (!seenEntitiesThisFrame.contains(entity) || !alive) {
                for (EntityPartKey partKey : entry.getValue()) {
                    Long instance = retainedInstances.remove(partKey);
                    if (instance != null) {
                        try {
                            api.sceneInstanceRemove(instance);
                        } catch (Exception ignored) {}
                    }
                    Long mesh = instanceMeshes.remove(partKey);
                    if (mesh != null && mesh != 0) {
                        try {
                            api.destroyMesh(mesh);
                        } catch (Exception ignored) {}
                    }
                }
                it.remove();
            }
        }
    }

    public void clearInstances() {
        for (Long instance : retainedInstances.values()) {
            if (instance != null && instance != 0) {
                try {
                    api.sceneInstanceRemove(instance);
                } catch (Exception ignored) {}
            }
        }
        retainedInstances.clear();
        instancesByEntity.clear();

        for (Long mesh : instanceMeshes.values()) {
            if (mesh != null && mesh != 0) {
                try {
                    api.destroyMesh(mesh);
                } catch (Exception ignored) {}
            }
        }
        instanceMeshes.clear();

        for (long mesh : transientMeshes) {
            try {
                api.destroyMesh(mesh);
            } catch (Exception ignored) {}
        }
        transientMeshes.clear();
    }

    public void destroy() {
        clearInstances();

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

        if (defaultWhiteMaterial != 0) {
            try {
                api.destroyMaterial(defaultWhiteMaterial);
            } catch (Exception ignored) {}
            defaultWhiteMaterial = 0;
        }

        if (solidBlendPNext != 0) {
            RemixBridge.freeExt(solidBlendPNext);
            solidBlendPNext = 0;
        }
        if (cutoutBlendPNext != 0) {
            RemixBridge.freeExt(cutoutBlendPNext);
            cutoutBlendPNext = 0;
        }
        if (translucentBlendPNext != 0) {
            RemixBridge.freeExt(translucentBlendPNext);
            translucentBlendPNext = 0;
        }
    }

    public void invalidateSkinTexture(ResourceLocation textureId) {
        if (textureId == null) return;
        Long texHandle = textureCache.remove(textureId);
        if (texHandle != null && texHandle != defaultWhiteTexture) {
            try {
                api.destroyTexture(texHandle);
            } catch (Exception ignored) {}
        }
        Iterator<Map.Entry<MaterialKey, Long>> it = materialCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MaterialKey, Long> entry = it.next();
            if (textureId.equals(entry.getKey().textureId())) {
                Long mat = entry.getValue();
                if (mat != null && mat != 0 && mat != defaultWhiteMaterial) {
                    try {
                        api.destroyMaterial(mat);
                    } catch (Exception ignored) {}
                }
                it.remove();
            }
        }
    }

    public void invalidateEntityMaterials(Object entity) {
        if (entity == null) return;
        List<EntityPartKey> parts = instancesByEntity.get(entity);
        if (parts != null) {
            for (EntityPartKey part : parts) {
                Long mesh = instanceMeshes.remove(part);
                if (mesh != null && mesh != 0) {
                    try {
                        api.destroyMesh(mesh);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public int getLastDrawCount() {
        return drawCount;
    }

    public int getLastMeshCreateCount() {
        return meshCreateCount;
    }
}
