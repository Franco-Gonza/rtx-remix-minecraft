/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain.capture;

import com.nvidia.rtxremix.forge.light.LightEntry;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Captured block-level mesh data for a single chunk section (16x16x16),
 * produced by {@link BlockQuadEmitter}.
 */
public record SectionBlockData(
    SectionPos sectionPos,
    SectionMeshData meshData,
    List<LightEntry> lights,
    long topFaceMask,
    long bottomFaceMask,
    long eastFaceMask,
    long westFaceMask,
    long southFaceMask,
    long northFaceMask,
    long[] opacityBitmap
) {
    public record BlockQuad(
        float[] positions,
        float[] uvs,
        float nx, float ny, float nz,
        int color,
        int colorIndex,
        ResourceLocation spriteId,
        float spriteU0, float spriteV0,
        float spriteU1, float spriteV1,
        String renderLayer,
        boolean isOverlay,
        boolean isFluid
    ) {}
}
