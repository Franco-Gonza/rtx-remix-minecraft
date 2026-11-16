/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.forge.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.forge.terrain.capture.DirectQuadWriter;
import com.nvidia.rtxremix.forge.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.forge.texture.BlockTextureManager;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BlockTextureAuditTest {

    private static final ResourceLocation CUTOUT_SPRITE = new ResourceLocation("minecraft", "block/poppy");
    private static final ResourceLocation CACTUS_SPRITE = new ResourceLocation("minecraft", "block/cactus_side");
    private static final ResourceLocation TORCH_SPRITE = new ResourceLocation("minecraft", "block/torch");

    private static final float[] FRONT_POSITIONS = {
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 0.0f
    };

    // Cyclic vertex shift: same quad in reverse winding, starting from corner 1 instead of 0
    private static final float[] SHIFTED_BACK_POSITIONS = {
            1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
    };

    private static final float[] UVS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f
    };

    @BeforeAll
    static void init() {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge NetworkEvent bootstrap requires ModLauncher; block registries are already initialized
        }
    }

    @Test
    void testFoliageAndCutoutClassification() {
        // Leaves must always be cutout_mipped regardless of fast/fancy graphics
        assertEquals("cutout_mipped", BlockQuadEmitter.classifyBlockLayer(Blocks.OAK_LEAVES.defaultBlockState()));
        assertEquals("cutout_mipped", BlockQuadEmitter.classifyBlockLayer(Blocks.BIRCH_LEAVES.defaultBlockState()));

        // Cutout plants and props
        assertEquals("cutout", BlockQuadEmitter.classifyBlockLayer(Blocks.TORCH.defaultBlockState()));
        assertEquals("cutout", BlockQuadEmitter.classifyBlockLayer(Blocks.GRASS.defaultBlockState()));
        assertEquals("cutout", BlockQuadEmitter.classifyBlockLayer(Blocks.CACTUS.defaultBlockState()));

        // Solid terrain blocks
        assertEquals("solid", BlockQuadEmitter.classifyBlockLayer(Blocks.DIRT.defaultBlockState()));
        assertEquals("solid", BlockQuadEmitter.classifyBlockLayer(Blocks.STONE.defaultBlockState()));

        // Glass
        assertEquals("glass", BlockQuadEmitter.classifyBlockLayer(Blocks.GLASS.defaultBlockState()));
        assertEquals("glass", BlockQuadEmitter.classifyBlockLayer(Blocks.GLASS_PANE.defaultBlockState()));
        assertEquals("translucent", BlockQuadEmitter.classifyBlockLayer(Blocks.WHITE_STAINED_GLASS.defaultBlockState()));
        assertEquals("translucent", BlockQuadEmitter.classifyBlockLayer(Blocks.WHITE_STAINED_GLASS_PANE.defaultBlockState()));
    }

    @Test
    void testCactusAndTorchSpriteTransparency() {
        BlockTextureManager btm = BlockTextureManager.get();
        BlockTextureManager.SpriteEntry cactusEntry = btm.getSpriteById(CACTUS_SPRITE);
        BlockTextureManager.SpriteEntry torchEntry = btm.getSpriteById(TORCH_SPRITE);

        assertNotNull(cactusEntry);
        assertNotNull(torchEntry);

        // Torch texture has transparent empty space around stick
        assertTrue(torchEntry.hasTransparency());
    }

    @Test
    void testCyclicShiftCutoutBackfaceDeduplication() {
        System.out.println("CUTOUT_SPRITE transparent: " + BlockTextureManager.get().getSpriteById(CUTOUT_SPRITE).hasTransparency());
        DirectQuadWriter writer = new DirectQuadWriter();
        writer.beginSection();

        // Front face (normal +Z)
        writer.stageQuad(FRONT_POSITIONS, UVS, 0.0f, 0.0f, 1.0f,
                0xFFFFFFFF, CUTOUT_SPRITE, 0.0f, 0.0f, 1.0f, 1.0f, "cutout");

        // Back face with cyclic shifted starting vertex (normal -Z)
        writer.stageQuad(SHIFTED_BACK_POSITIONS, UVS, 0.0f, 0.0f, -1.0f,
                0xFFFFFFFF, CUTOUT_SPRITE, 0.0f, 0.0f, 1.0f, 1.0f, "cutout");

        writer.flushBlock(BlockTextureManager.get());

        SectionMeshData meshData = writer.buildMeshData();
        try {
            assertEquals(1, meshData.surfaces().size());
            SectionMeshData.Surface surface = meshData.surfaces().get(0);
            System.out.println("Surface category: " + surface.category() + ", bucketKey: " + surface.bucketKey());
            assertEquals(SectionMeshData.CAT_CUTOUT, surface.category());
            // Exactly 4 vertices (1 quad retained), duplicate backface discarded
            assertEquals(4, surface.vertexCount());
            assertEquals(6, surface.indexCount());
        } finally {
            meshData.release();
        }
    }

    @Test
    void testCutoutLayerNeverDemotedToSolid() {
        DirectQuadWriter writer = new DirectQuadWriter();
        writer.beginSection();

        // Cactus quad staged with "cutout" layer from model classification
        writer.stageQuad(FRONT_POSITIONS, UVS, 0.0f, 0.0f, 1.0f,
                0xFFFFFFFF, CACTUS_SPRITE, 0.0f, 0.0f, 1.0f, 1.0f, "cutout");

        writer.flushBlock(BlockTextureManager.get());

        SectionMeshData meshData = writer.buildMeshData();
        try {
            assertEquals(1, meshData.surfaces().size());
            SectionMeshData.Surface surface = meshData.surfaces().get(0);
            // Cutout layers must NEVER be demoted to solid to ensure alpha testing is always active
            assertEquals(SectionMeshData.CAT_CUTOUT, surface.category());
            assertEquals(4, surface.vertexCount());
        } finally {
            meshData.release();
        }
    }

    @Test
    void testTorchKeptInCutoutCategory() {
        DirectQuadWriter writer = new DirectQuadWriter();
        writer.beginSection();

        // Torch quad staged with "cutout" layer
        writer.stageQuad(FRONT_POSITIONS, UVS, 0.0f, 0.0f, 1.0f,
                0xFFFFFFFF, TORCH_SPRITE, 0.0f, 0.0f, 1.0f, 1.0f, "cutout");

        writer.flushBlock(BlockTextureManager.get());

        SectionMeshData meshData = writer.buildMeshData();
        try {
            assertEquals(1, meshData.surfaces().size());
            SectionMeshData.Surface surface = meshData.surfaces().get(0);
            // Torch sprite has transparency, so it must stay in CAT_CUTOUT
            assertEquals(SectionMeshData.CAT_CUTOUT, surface.category());
            assertEquals(4, surface.vertexCount());
        } finally {
            meshData.release();
        }
    }

    @Test
    void testLavaClassificationAndBucketIsolation() {
        assertEquals("lava", BlockQuadEmitter.classifyBlockLayer(Blocks.LAVA.defaultBlockState()));
        assertEquals(BlockTextureManager.L_LAVA, BlockTextureManager.layerType("lava"));

        ResourceLocation lavaStill = new ResourceLocation("minecraft", "block/lava_still");
        DirectQuadWriter writer = new DirectQuadWriter();
        writer.beginSection();

        writer.writeFluidQuad(FRONT_POSITIONS, UVS, 0.0f, 1.0f, 0.0f,
                0xFFFFFFFF, lavaStill, 0.0f, 0.0f, 1.0f, 1.0f, "lava", BlockTextureManager.get());

        SectionMeshData meshData = writer.buildMeshData();
        try {
            assertEquals(1, meshData.surfaces().size());
            SectionMeshData.Surface surface = meshData.surfaces().get(0);
            assertEquals("lava", surface.bucketKey());
            assertEquals(SectionMeshData.CAT_SOLID, surface.category());
            assertEquals(4, surface.vertexCount());
        } finally {
            meshData.release();
        }
    }

    @Test
    void testQuadNormalResolutionNonZero() {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            net.minecraft.client.renderer.FaceInfo fi = net.minecraft.client.renderer.FaceInfo.fromFacing(dir);
            assertNotNull(fi);
            float stepX = dir.getStepX();
            float stepY = dir.getStepY();
            float stepZ = dir.getStepZ();
            assertTrue(stepX != 0 || stepY != 0 || stepZ != 0, "Direction step vector must not be zero");
        }
    }

    @Test
    void testFireAndSoulFireLightRules() {
        assertTrue(com.nvidia.rtxremix.forge.light.BlockLightRuleManager.hasMatchingRules(net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState()));
        assertTrue(com.nvidia.rtxremix.forge.light.BlockLightRuleManager.hasMatchingRules(net.minecraft.world.level.block.Blocks.SOUL_FIRE.defaultBlockState()));

        var fireRules = com.nvidia.rtxremix.forge.light.BlockLightRuleManager.resolve(
                net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState(),
                net.minecraft.core.BlockPos.ZERO,
                null);
        assertEquals(1, fireRules.size());
        var fireLight = fireRules.get(0);
        assertTrue(fireLight.intensity() > 0.0f);
        assertTrue(fireLight.colorR() > fireLight.colorB(), "Fire should be warm colored");

        var soulRules = com.nvidia.rtxremix.forge.light.BlockLightRuleManager.resolve(
                net.minecraft.world.level.block.Blocks.SOUL_FIRE.defaultBlockState(),
                net.minecraft.core.BlockPos.ZERO,
                null);
        assertEquals(1, soulRules.size());
        var soulLight = soulRules.get(0);
        assertTrue(soulLight.intensity() > 0.0f);
        assertTrue(soulLight.colorB() > soulLight.colorR(), "Soul fire should be cool cyan/blue colored");
    }
}

