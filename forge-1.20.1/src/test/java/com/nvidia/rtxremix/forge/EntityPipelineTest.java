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
}
