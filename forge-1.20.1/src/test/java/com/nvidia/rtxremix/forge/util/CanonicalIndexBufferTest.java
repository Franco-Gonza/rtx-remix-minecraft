/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.util;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalIndexBufferTest {

    @AfterAll
    static void cleanup() {
        CanonicalIndexBuffer.free();
    }

    @Test
    void testCanonicalPattern() {
        int indexCount = 12; // 2 quads
        ByteBuffer buf = CanonicalIndexBuffer.getBuffer(indexCount);

        assertNotNull(buf);
        assertTrue(buf.isDirect());
        assertEquals(indexCount * Integer.BYTES, buf.remaining());

        // Quad 0: 0, 2, 1, 0, 3, 2
        assertEquals(0, buf.getInt());
        assertEquals(2, buf.getInt());
        assertEquals(1, buf.getInt());
        assertEquals(0, buf.getInt());
        assertEquals(3, buf.getInt());
        assertEquals(2, buf.getInt());

        // Quad 1: 4, 6, 5, 4, 7, 6
        assertEquals(4, buf.getInt());
        assertEquals(6, buf.getInt());
        assertEquals(5, buf.getInt());
        assertEquals(4, buf.getInt());
        assertEquals(7, buf.getInt());
        assertEquals(6, buf.getInt());
    }

    @Test
    void testDynamicCapacityExpansion() {
        // Request indices exceeding initial 32768 quads (196608 indices)
        int largeIndexCount = 40000 * 6; // 240,000 indices
        ByteBuffer buf = CanonicalIndexBuffer.getBuffer(largeIndexCount);

        assertNotNull(buf);
        assertEquals(largeIndexCount * Integer.BYTES, buf.remaining());

        // Check quad 39999
        int q = 39999;
        int base = q * 4;
        buf.position(q * 6 * Integer.BYTES);
        assertEquals(base, buf.getInt());
        assertEquals(base + 2, buf.getInt());
        assertEquals(base + 1, buf.getInt());
        assertEquals(base, buf.getInt());
        assertEquals(base + 3, buf.getInt());
        assertEquals(base + 2, buf.getInt());
    }
}
