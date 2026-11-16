/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.forge.entity.EntityRenderContext;
import com.nvidia.rtxremix.forge.logic.LogicChannels;
import com.nvidia.rtxremix.forge.util.DirectBufferPool;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class EntityPipelineTest {

    @Test
    void testEntityRenderContext() {
        EntityRenderContext ctx = EntityRenderContext.get();
        ctx.begin(true);
        assertTrue(ctx.isActive());
        assertTrue(ctx.isRemixPass());

        Matrix4f pose = new Matrix4f().identity().translate(1, 2, 3);
        int boneIdx = ctx.pushBone(pose);
        assertEquals(0, boneIdx);
        assertEquals(1, ctx.getBoneCount());

        float[] snapshot = ctx.snapshotBoneTransforms(null);
        assertNotNull(snapshot);
        assertEquals(12, snapshot.length);

        ctx.end();
        assertFalse(ctx.isActive());
    }

    @Test
    void testDirectBufferPool() {
        ByteBuffer buf = DirectBufferPool.acquire(1024);
        assertNotNull(buf);
        assertTrue(buf.isDirect());
        assertTrue(buf.capacity() >= 1024);

        DirectBufferPool.release(buf);
    }

    @Test
    void testHardcodedVertexPacking() {
        int packed = HardcodedVertex.packHalf2x16(1.0f, 0.5f);
        assertNotEquals(0, packed);
    }

    @Test
    void testLogicChannels() {
        assertEquals("mc.underwater", LogicChannels.UNDERWATER);
        assertEquals("mc.biome.", LogicChannels.BIOME_PREFIX);
    }

    @Test
    void testLightShapesAndKeys() {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(1, 2, 3);
        net.minecraft.resources.ResourceLocation ruleId = new net.minecraft.resources.ResourceLocation("minecraft", "torch");
        com.nvidia.rtxremix.forge.light.BlockLightKey key1 = new com.nvidia.rtxremix.forge.light.BlockLightKey(pos, ruleId, 0);
        com.nvidia.rtxremix.forge.light.BlockLightKey key2 = new com.nvidia.rtxremix.forge.light.BlockLightKey(pos, ruleId, 0);
        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());

        assertNotNull(com.nvidia.rtxremix.forge.light.LightShape.valueOf("SPHERE"));
        assertNotNull(com.nvidia.rtxremix.forge.light.LightShape.valueOf("CYLINDER"));
        assertNotNull(com.nvidia.rtxremix.forge.light.LightShape.valueOf("RECT"));
        assertNotNull(com.nvidia.rtxremix.forge.light.LightShape.valueOf("DISK"));
    }

    @Test
    void testCapturingConsumerTransform() {
        com.nvidia.rtxremix.forge.entity.RemixCapturingConsumer consumer =
                new com.nvidia.rtxremix.forge.entity.RemixCapturingConsumer();
        consumer.reset(new net.minecraft.resources.ResourceLocation("test", "tex"), "solid");

        Matrix4f pose = new Matrix4f().identity().translate(10.0f, 20.0f, 30.0f);
        org.joml.Matrix3f norm = new org.joml.Matrix3f().identity();

        consumer.vertex(pose, 1.0f, 2.0f, 3.0f);
        consumer.normal(norm, 0.0f, 1.0f, 0.0f);
        consumer.uv(0.5f, 0.5f);
        consumer.color(255, 255, 255, 255);
        consumer.endVertex();

        assertEquals(1, consumer.getVertexCount());
        assertEquals(11.0f, consumer.getPositionsX()[0], 1.0E-4f);
        assertEquals(22.0f, consumer.getPositionsY()[0], 1.0E-4f);
        assertEquals(33.0f, consumer.getPositionsZ()[0], 1.0E-4f);
        assertEquals(0.0f, consumer.getNormalsX()[0], 1.0E-4f);
        assertEquals(1.0f, consumer.getNormalsY()[0], 1.0E-4f);
        assertEquals(0.0f, consumer.getNormalsZ()[0], 1.0E-4f);
    }

    @Test
    void testTerrainInstanceBlendConfigurations() {
        com.nvidia.rtxremix.InstanceInfoBlendEXT solid = com.nvidia.rtxremix.InstanceInfoBlendEXT.textureTimesVertexColor();
        assertNotNull(solid);
        assertEquals(0, solid.alphaTestEnabled);
        assertEquals(0, solid.alphaBlendEnabled);
        assertEquals(com.nvidia.rtxremix.InstanceInfoBlendEXT.RT_TEX_OP_MODULATE, solid.textureColorOperation);

        com.nvidia.rtxremix.InstanceInfoBlendEXT cutout = com.nvidia.rtxremix.InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(128);
        assertNotNull(cutout);
        assertEquals(1, cutout.alphaTestEnabled);
        assertEquals((byte) 128, cutout.alphaTestReferenceValue);
        assertEquals(0, cutout.alphaBlendEnabled);

        com.nvidia.rtxremix.InstanceInfoBlendEXT overlay = com.nvidia.rtxremix.InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(1);
        assertNotNull(overlay);
        assertEquals(1, overlay.alphaBlendEnabled);
        assertEquals(1, overlay.alphaTestEnabled);

        assertEquals(1 << 0, com.nvidia.rtxremix.MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS);
        assertNotEquals(0, com.nvidia.rtxremix.InstanceCategoryBit.DECAL_STATIC);
    }

    @Test
    void testCapturableTextureOnEntityRenderType() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {}
        net.minecraft.resources.ResourceLocation cowTex = new net.minecraft.resources.ResourceLocation("minecraft", "textures/entity/cow/cow.png");
        net.minecraft.client.renderer.RenderType layer = net.minecraft.client.renderer.RenderType.entityCutout(cowTex);
        java.util.Optional<net.minecraft.resources.ResourceLocation> extracted = com.nvidia.rtxremix.forge.entity.RenderLayerUtils.capturableTexture(layer);
        assertTrue(extracted.isPresent(), "capturableTexture should resolve texture for entityCutout");
        assertEquals(cowTex, extracted.get());
    }

    @Test
    void testTopologySupportForTrianglesAndFans() {
        net.minecraft.resources.ResourceLocation dummyTex = new net.minecraft.resources.ResourceLocation("alexscaves", "textures/entity/laser.png");
        DummyCustomRenderType triLayer = new DummyCustomRenderType("alexscaves:laser", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES);
        DummyCustomRenderType fanLayer = new DummyCustomRenderType("citadel:beam_fan", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLE_FAN);
        DummyCustomRenderType stripLayer = new DummyCustomRenderType("citadel:beam_strip", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLE_STRIP);
        DummyCustomRenderType quadLayer = new DummyCustomRenderType("minecraft:quads", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);

        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.Topology.TRIANGLES,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.topology(triLayer).orElse(null));
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.Topology.TRIANGLE_FAN,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.topology(fanLayer).orElse(null));
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.Topology.TRIANGLE_STRIP,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.topology(stripLayer).orElse(null));
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.Topology.QUADS,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.topology(quadLayer).orElse(null));
    }

    @Test
    void testCapturingConsumerDynamicResizeBeyond16384() {
        com.nvidia.rtxremix.forge.entity.RemixCapturingConsumer consumer =
                new com.nvidia.rtxremix.forge.entity.RemixCapturingConsumer();
        consumer.reset(new net.minecraft.resources.ResourceLocation("bookofdragons", "dragon_mesh"), "cutout");

        int targetVertices = 20000;
        for (int i = 0; i < targetVertices; i++) {
            consumer.vertex(i, i * 0.5, i * 0.25);
            consumer.color(255, 255, 255, 255);
            consumer.uv(0.1f, 0.2f);
            consumer.normal(0.0f, 1.0f, 0.0f);
            consumer.endVertex();
        }

        assertEquals(targetVertices, consumer.getVertexCount(), "Consumer must not drop vertices beyond 16384 initial capacity");
        assertEquals(19999.0f, consumer.getPositionsX()[19999], 1.0E-4f);
        assertEquals(19999.0 * 0.5f, consumer.getPositionsY()[19999], 1.0E-4f);
    }

    @Test
    void testStandardPlayerAndEntityLayersAreCutout() {
        net.minecraft.resources.ResourceLocation dummyTex = new net.minecraft.resources.ResourceLocation("test", "tex");
        DummyCustomRenderType playerSkin = new DummyCustomRenderType("minecraft:entity_translucent[minecraft:textures/entity/player/slim/steve.png]", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        DummyCustomRenderType itemLayer = new DummyCustomRenderType("minecraft:item_entity_translucent_cull", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        DummyCustomRenderType armorCutout = new DummyCustomRenderType("minecraft:armor_cutout_no_cull", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        DummyCustomRenderType entitySolid = new DummyCustomRenderType("minecraft:entity_solid", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        DummyCustomRenderType glassLayer = new DummyCustomRenderType("minecraft:entity_stained_glass", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);

        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.CUTOUT,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.surfaceMode(playerSkin),
                "Player entity_translucent skin must classify as CUTOUT, not physical TRANSLUCENT glass");
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.CUTOUT,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.surfaceMode(itemLayer));
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.CUTOUT,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.surfaceMode(armorCutout));
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.CUTOUT,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.surfaceMode(entitySolid));
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.GLASS,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.surfaceMode(glassLayer));
    }

    @Test
    void testSurfaceModeClassificationForModdedLayers() {
        net.minecraft.resources.ResourceLocation dummyTex = new net.minecraft.resources.ResourceLocation("test", "tex");
        DummyCustomRenderType tombstoneGhost = new DummyCustomRenderType("tombstone:ghostly_shape", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        DummyCustomRenderType alexsMobsRay = new DummyCustomRenderType("alexsmobs:ghost_ray", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLE_STRIP);
        DummyCustomRenderType dragonWing = new DummyCustomRenderType("bookofdragons:wing_membrane", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        DummyCustomRenderType radioactiveGlow = new DummyCustomRenderType("alexscaves:radioactive_glow", dummyTex, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);

        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.TRANSLUCENT,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.surfaceMode(tombstoneGhost));
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.TRANSLUCENT,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.surfaceMode(alexsMobsRay));
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.TRANSLUCENT,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.surfaceMode(dragonWing));
        assertEquals(com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.EMISSIVE,
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.surfaceMode(radioactiveGlow));
    }

    @Test
    void testCustomRenderTypeTextureReflection() {
        net.minecraft.resources.ResourceLocation expectedLoc = new net.minecraft.resources.ResourceLocation("alexscaves", "textures/entity/ray.png");
        DummyCustomRenderType customLayer = new DummyCustomRenderType("alexscaves:ray_custom", expectedLoc, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES);

        java.util.Optional<net.minecraft.resources.ResourceLocation> resolved =
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.capturableTexture(customLayer);
        assertTrue(resolved.isPresent(), "Direct reflection should resolve custom texture field");
        assertEquals(expectedLoc, resolved.get());
    }

    @Test
    void testBookOfDragonsSpeciesTextureResolution() {
        DummyCustomRenderType nightfuryLayer = new DummyCustomRenderType(
                "liberanimalium_species_cutout_nightfury", null, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        DummyCustomRenderType nadderLayer = new DummyCustomRenderType(
                "liberanimalium_species_translucent_deadly_nadder", null, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);

        java.util.Optional<net.minecraft.resources.ResourceLocation> nightfuryTex =
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.capturableTexture(nightfuryLayer);
        assertTrue(nightfuryTex.isPresent(), "Heuristic should resolve species texture for nightfury");
        assertEquals("bookofdragons", nightfuryTex.get().getNamespace());
        assertTrue(nightfuryTex.get().getPath().contains("nightfury"));

        java.util.Optional<net.minecraft.resources.ResourceLocation> nadderTex =
                com.nvidia.rtxremix.forge.entity.RenderLayerUtils.capturableTexture(nadderLayer);
        assertTrue(nadderTex.isPresent(), "Heuristic should resolve species texture for deadly_nadder");
        assertEquals("bookofdragons", nadderTex.get().getNamespace());
        assertTrue(nadderTex.get().getPath().contains("deadly_nadder"));
    }

    @Test
    void testRemixEntityPassLifecycle() {
        com.nvidia.rtxremix.forge.entity.RemixEntityPass pass = new com.nvidia.rtxremix.forge.entity.RemixEntityPass();
        pass.resetFrame();
    }

    @Test
    void testDynamicSkinMaterialCacheInvalidation() throws Exception {
        java.lang.reflect.Constructor<com.nvidia.rtxremix.RemixAPI> apiCtor =
                com.nvidia.rtxremix.RemixAPI.class.getDeclaredConstructor();
        apiCtor.setAccessible(true);
        com.nvidia.rtxremix.RemixAPI api = apiCtor.newInstance();

        com.nvidia.rtxremix.forge.entity.RemixEntitySubmitter submitter =
                new com.nvidia.rtxremix.forge.entity.RemixEntitySubmitter(api);

        net.minecraft.resources.ResourceLocation skinLoc =
                new net.minecraft.resources.ResourceLocation("minecraft", "skins/test_player_skin");
        net.minecraft.resources.ResourceLocation otherLoc =
                new net.minecraft.resources.ResourceLocation("minecraft", "skins/other_player_skin");

        java.lang.reflect.Field texCacheField =
                com.nvidia.rtxremix.forge.entity.RemixEntitySubmitter.class.getDeclaredField("textureCache");
        texCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<net.minecraft.resources.ResourceLocation, Long> textureCache =
                (java.util.Map<net.minecraft.resources.ResourceLocation, Long>) texCacheField.get(submitter);

        java.lang.reflect.Field matCacheField =
                com.nvidia.rtxremix.forge.entity.RemixEntitySubmitter.class.getDeclaredField("materialCache");
        matCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Object, Long> materialCache =
                (java.util.Map<Object, Long>) matCacheField.get(submitter);

        textureCache.put(skinLoc, 1001L);
        textureCache.put(otherLoc, 1002L);

        Class<?>[] declaredClasses = com.nvidia.rtxremix.forge.entity.RemixEntitySubmitter.class.getDeclaredClasses();
        Class<?> matKeyClass = null;
        for (Class<?> cl : declaredClasses) {
            if (cl.getSimpleName().equals("MaterialKey")) {
                matKeyClass = cl;
                break;
            }
        }
        assertNotNull(matKeyClass, "MaterialKey inner record must exist");
        java.lang.reflect.Constructor<?> matKeyCtor = matKeyClass.getDeclaredConstructors()[0];
        matKeyCtor.setAccessible(true);

        Object skinCutoutKey = matKeyCtor.newInstance(skinLoc, com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.CUTOUT);
        Object skinOpaqueKey = matKeyCtor.newInstance(skinLoc, com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.OPAQUE);
        Object otherKey = matKeyCtor.newInstance(otherLoc, com.nvidia.rtxremix.forge.entity.RenderLayerUtils.SurfaceMode.CUTOUT);

        materialCache.put(skinCutoutKey, 2001L);
        materialCache.put(skinOpaqueKey, 2002L);
        materialCache.put(otherKey, 2003L);

        submitter.invalidateSkinTexture(skinLoc);

        assertFalse(textureCache.containsKey(skinLoc), "Invalidated skin texture must be removed from textureCache");
        assertTrue(textureCache.containsKey(otherLoc), "Other textures must remain untouched");
        assertEquals(1002L, textureCache.get(otherLoc));

        assertFalse(materialCache.containsKey(skinCutoutKey), "Skin cutout material must be purged from materialCache");
        assertFalse(materialCache.containsKey(skinOpaqueKey), "Skin opaque material must be purged from materialCache");
        assertTrue(materialCache.containsKey(otherKey), "Other materials must remain untouched");
        assertEquals(2003L, materialCache.get(otherKey));

        submitter.invalidateSkinTexture(null);
        submitter.invalidateSkinTexture(new net.minecraft.resources.ResourceLocation("minecraft", "unrelated"));
        assertTrue(textureCache.containsKey(otherLoc));
        assertTrue(materialCache.containsKey(otherKey));
    }

    @Test
    void testForgeNativeExtractorStagingBufferMerge(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws java.io.IOException {
        java.nio.file.Path testConf = tempDir.resolve("test_rtx.conf");
        java.nio.file.Files.writeString(testConf, "rtx.dlfg.enable = True" + System.lineSeparator());

        com.nvidia.rtxremix.forge.runtime.ForgeNativeExtractor.mergeCriticalConfigKeys(testConf);

        String updated = java.nio.file.Files.readString(testConf);
        assertTrue(updated.contains("rtx.textureManager.stagingBufferSizeMiB = 512"), "Should merge staging buffer size");
        assertTrue(updated.contains("dxvk.otherMemoryChunkSizeMB = 512"), "Should merge dxvk other memory chunk size");
        assertTrue(updated.contains("rtx.reflex.mode = 2"), "Should merge reflex mode");

        // Test upgrading previous smaller configuration (< 512)
        java.nio.file.Path testLegacyConf = tempDir.resolve("legacy_rtx.conf");
        java.nio.file.Files.writeString(testLegacyConf,
                "rtx.textureManager.stagingBufferSizeMiB = 256" + System.lineSeparator() +
                "dxvk.otherMemoryChunkSizeMB = 256" + System.lineSeparator());
        com.nvidia.rtxremix.forge.runtime.ForgeNativeExtractor.mergeCriticalConfigKeys(testLegacyConf);
        String upgraded = java.nio.file.Files.readString(testLegacyConf);
        assertTrue(upgraded.contains("rtx.textureManager.stagingBufferSizeMiB = 512"), "Should upgrade legacy staging buffer size to 512");
        assertTrue(upgraded.contains("dxvk.otherMemoryChunkSizeMB = 512"), "Should upgrade legacy chunk size to 512");

        // Test preserving user-configured higher values (>= 512)
        java.nio.file.Path testUserHigherConf = tempDir.resolve("user_higher_rtx.conf");
        java.nio.file.Files.writeString(testUserHigherConf,
                "rtx.textureManager.stagingBufferSizeMiB = 1024" + System.lineSeparator() +
                "dxvk.otherMemoryChunkSizeMB = 1024" + System.lineSeparator());
        com.nvidia.rtxremix.forge.runtime.ForgeNativeExtractor.mergeCriticalConfigKeys(testUserHigherConf);
        String userHigher = java.nio.file.Files.readString(testUserHigherConf);
        assertTrue(userHigher.contains("rtx.textureManager.stagingBufferSizeMiB = 1024"), "Should preserve user custom staging buffer size");
        assertTrue(userHigher.contains("dxvk.otherMemoryChunkSizeMB = 1024"), "Should preserve user custom chunk size");
    }

    static class DummyCustomRenderType extends net.minecraft.client.renderer.RenderType {
        private final net.minecraft.resources.ResourceLocation customTexture;

        DummyCustomRenderType(String name, net.minecraft.resources.ResourceLocation tex, com.mojang.blaze3d.vertex.VertexFormat.Mode mode) {
            super(name, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_TEX, mode, 256, false, false, () -> {}, () -> {});
            this.customTexture = tex;
        }
    }
}
