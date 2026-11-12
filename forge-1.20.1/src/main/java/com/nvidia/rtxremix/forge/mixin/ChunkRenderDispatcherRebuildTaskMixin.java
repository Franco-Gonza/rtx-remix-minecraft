/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.forge.client.ForgeRemixRenderer;
import com.nvidia.rtxremix.forge.terrain.VanillaSectionBuilds;
import com.nvidia.rtxremix.forge.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.forge.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.forge.terrain.capture.SectionBlockData;
import com.nvidia.rtxremix.forge.texture.BlockTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts chunk compilation on worker threads in Forge 1.20.1 to capture
 * block and fluid geometry inline for RTX Remix path tracing.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask")
public abstract class ChunkRenderDispatcherRebuildTaskMixin {

    @Shadow(aliases = {"f_112859_", "this$1"}) @Final
    ChunkRenderDispatcher.RenderChunk this$1;

    @Shadow
    protected RenderChunkRegion region;

    private static final ThreadLocal<BlockDataQueue.CaptureToken> rtxremix$blockDataCaptureToken = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> rtxremix$captureForBuild = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<SectionPos> rtxremix$sectionPos = new ThreadLocal<>();

    private static boolean rtxremix$capturing() {
        return rtxremix$captureForBuild.get();
    }

    @Inject(method = "compile", at = @At("HEAD"))
    private void rtxremix$onCompileHead(
            float playerX, float playerY, float playerZ,
            ChunkBufferBuilderPack buffers,
            CallbackInfoReturnable<?> cir) {

        boolean capturing = VanillaSectionBuilds.beginBuild(ForgeRemixRenderer::isRtxEnabled);
        rtxremix$captureForBuild.set(capturing);
        if (!capturing) return;

        BlockPos origin = this$1.getOrigin();
        SectionPos sectionPos = SectionPos.of(origin);
        rtxremix$sectionPos.set(sectionPos);

        rtxremix$blockDataCaptureToken.set(BlockDataQueue.beginCapture(sectionPos.getX(), sectionPos.getZ()));

        if (this.region == null) return;

        BlockTextureManager tex = BlockTextureManager.get();
        BlockRenderDispatcher brm = Minecraft.getInstance().getBlockRenderer();
        BlockQuadEmitter.threadLocal().beginSection(sectionPos, tex, brm);
    }

    @Redirect(method = "compile",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean rtxremix$captureBlock(BlockState state, BlockGetter world, BlockPos pos) {
        boolean opaque = state.isSolidRender(world, pos);
        BlockQuadEmitter emitter = BlockQuadEmitter.current();
        if (emitter != null && emitter.isActive()) {
            emitter.processBlock(state, world, pos, opaque);
            if (BlockQuadEmitter.getRenderTypeCached(state) == RenderShape.MODEL) {
                emitter.emitBlock(state, pos, (BlockAndTintGetter) world);
            }
        }
        return opaque;
    }

    @Redirect(method = "compile",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape rtxremix$renderTypeForVanilla(BlockState state) {
        return rtxremix$capturing()
                ? RenderShape.INVISIBLE
                : BlockQuadEmitter.getRenderTypeCached(state);
    }

    @Redirect(method = "compile",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderLiquid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
    private void rtxremix$redirectFluid(BlockRenderDispatcher brm,
                                        BlockPos pos, BlockAndTintGetter world, VertexConsumer consumer,
                                        BlockState blockState, FluidState fluidState) {
        if (rtxremix$capturing()) {
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            if (!emitter.isActive()) return;
            emitter.emitFluid(pos, world, fluidState);
        } else {
            brm.renderLiquid(pos, world, consumer, blockState, fluidState);
        }
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void rtxremix$onCompileReturn(
            float playerX, float playerY, float playerZ,
            ChunkBufferBuilderPack buffers,
            CallbackInfoReturnable<?> cir) {

        try {
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            if (!emitter.isActive()) return;

            SectionPos sectionPos = rtxremix$sectionPos.get();
            if (sectionPos != null) {
                SectionBlockData blockData = emitter.finishSection(sectionPos);
                BlockDataQueue.enqueue(blockData, rtxremix$blockDataCaptureToken.get());
            }
        } finally {
            rtxremix$blockDataCaptureToken.remove();
            rtxremix$sectionPos.remove();
            VanillaSectionBuilds.endBuild(rtxremix$capturing());
            rtxremix$captureForBuild.remove();
        }
    }
}
