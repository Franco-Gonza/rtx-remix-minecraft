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
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.resources.ResourceLocation cowTex = new net.minecraft.resources.ResourceLocation("minecraft", "textures/entity/cow/cow.png");
        net.minecraft.client.renderer.RenderType layer = net.minecraft.client.renderer.RenderType.entityCutout(cowTex);
        java.util.Optional<net.minecraft.resources.ResourceLocation> extracted = com.nvidia.rtxremix.forge.entity.RenderLayerUtils.capturableTexture(layer);
        assertTrue(extracted.isPresent(), "capturableTexture should resolve texture for entityCutout");
        assertEquals(cowTex, extracted.get());
    }
}
