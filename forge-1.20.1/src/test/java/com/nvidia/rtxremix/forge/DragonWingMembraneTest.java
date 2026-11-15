/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.forge.compatibility.DragonWingMembraneHelper;
import com.nvidia.rtxremix.forge.entity.RemixCapturingConsumer;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DragonWingMembraneTest {

    @Test
    @DisplayName("DoubleSidedConsumer duplicates quads with reversed winding and inverted normals")
    void testDoubleSidedConsumerEmitsBothWindings() {
        RemixCapturingConsumer capturing = new RemixCapturingConsumer();
        capturing.reset(new ResourceLocation("bookofdragons", "textures/entity/dragon/nightfury.png"), "cutout");

        DragonWingMembraneHelper.DoubleSidedConsumer doubleSided =
                new DragonWingMembraneHelper.DoubleSidedConsumer(capturing);

        // Emit 4 vertices of a quad representing a dragon wing membrane panel
        // (x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz)
        doubleSided.vertex(0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0, 0, 0.0f, 1.0f, 0.0f);
        doubleSided.vertex(1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0, 0, 0.0f, 1.0f, 0.0f);
        doubleSided.vertex(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0, 0, 0.0f, 1.0f, 0.0f);
        doubleSided.vertex(0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 0, 0, 0.0f, 1.0f, 0.0f);

        // Expect exactly 8 vertices captured (4 front quad + 4 back quad)
        assertEquals(8, capturing.getVertexCount());

        float[] px = capturing.getPositionsX();
        float[] pz = capturing.getPositionsZ();
        float[] ny = capturing.getNormalsY();

        // Front quad: v0 (0,0), v1 (1,0), v2 (1,1), v3 (0,1), normal +1.0
        assertEquals(0.0f, px[0], 1e-4f);
        assertEquals(0.0f, pz[0], 1e-4f);
        assertEquals(1.0f, ny[0], 1e-4f);

        assertEquals(1.0f, px[1], 1e-4f);
        assertEquals(0.0f, pz[1], 1e-4f);
        assertEquals(1.0f, ny[1], 1e-4f);

        assertEquals(1.0f, px[2], 1e-4f);
        assertEquals(1.0f, pz[2], 1e-4f);
        assertEquals(1.0f, ny[2], 1e-4f);

        assertEquals(0.0f, px[3], 1e-4f);
        assertEquals(1.0f, pz[3], 1e-4f);
        assertEquals(1.0f, ny[3], 1e-4f);

        // Back quad with reversed winding: v3, v2, v1, v0 with inverted normal -1.0
        assertEquals(0.0f, px[4], 1e-4f);
        assertEquals(1.0f, pz[4], 1e-4f);
        assertEquals(-1.0f, ny[4], 1e-4f);

        assertEquals(1.0f, px[5], 1e-4f);
        assertEquals(1.0f, pz[5], 1e-4f);
        assertEquals(-1.0f, ny[5], 1e-4f);

        assertEquals(1.0f, px[6], 1e-4f);
        assertEquals(0.0f, pz[6], 1e-4f);
        assertEquals(-1.0f, ny[6], 1e-4f);

        assertEquals(0.0f, px[7], 1e-4f);
        assertEquals(0.0f, pz[7], 1e-4f);
        assertEquals(-1.0f, ny[7], 1e-4f);
    }

    @Test
    @DisplayName("DragonWingMembraneHelper safely handles null or non-Fauna context without throwing")
    void testSafeNonFaunaContextHandling() {
        assertDoesNotThrow(() -> DragonWingMembraneHelper.renderMembranes(null));
        assertDoesNotThrow(() -> DragonWingMembraneHelper.renderMembranes(new Object()));
    }
}
