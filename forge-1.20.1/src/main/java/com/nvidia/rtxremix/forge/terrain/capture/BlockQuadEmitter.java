/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain.capture;

import com.nvidia.rtxremix.forge.light.BlockLightRuleManager;
import com.nvidia.rtxremix.forge.light.LightEntry;
import com.nvidia.rtxremix.forge.profiling.ProfileScope;
import com.nvidia.rtxremix.forge.texture.BlockTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-block mesh emission for RTX Remix on Forge 1.20.1.
 * Passes Forge's ModelData into getQuads for modded blocks (Dynamic Trees, Chisel)
 * and resolves fluid textures and tinting via IClientFluidTypeExtensions.
 */
public final class BlockQuadEmitter {

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    private static final Direction[] ALL_DIRECTIONS = {
        null, Direction.UP, Direction.DOWN, Direction.NORTH,
        Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static final ThreadLocal<BlockQuadEmitter> INSTANCE = new ThreadLocal<>();

    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(42);
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidCheckPos = new BlockPos.MutableBlockPos();
    private final DirectQuadWriter writer = new DirectQuadWriter();
    private final ModelSampling modelSampling = new ModelSampling();
    private final List<LightEntry> lights = new ArrayList<>();

    private static final int TINT_CACHE_SIZE = 8;
    private static final int SECTION_SIZE = 16;
    private static final int LOCAL_BLOCK_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int WATER_COLOR_CACHE_SIZE = 4 * 2 * 4;
    private static final float WATER_CEILING_CULL_MIN_HEIGHT = 8.0f / 9.0f;
    private static final float WATER_HEIGHT_EPSILON = 0.0001f;
    private static final int WATER_TOP_CULL_RADIUS = 2;
    private static final int WATER_TOP_CULL_WIDTH = WATER_TOP_CULL_RADIUS * 2 + 1;
    private static final int WATER_TOP_CULL_CELL_COUNT = WATER_TOP_CULL_WIDTH * WATER_TOP_CULL_WIDTH;
    private static final int WATER_TOP_CACHE_WIDTH = SECTION_SIZE + WATER_TOP_CULL_RADIUS * 2;
    private static final int WATER_TOP_CACHE_CELL_COUNT =
            WATER_TOP_CACHE_WIDTH * SECTION_SIZE * WATER_TOP_CACHE_WIDTH;

    private static volatile RenderShape[] renderTypeByStateId = new RenderShape[0];

    private final float[] tempPositions = new float[12];
    private final float[] tempUvs = new float[8];
    private final int[] tintCache = new int[TINT_CACHE_SIZE];
    private final int[] waterColorCache = new int[WATER_COLOR_CACHE_SIZE];
    private final byte[] waterTopCellCache = new byte[WATER_TOP_CACHE_CELL_COUNT];
    private final int[] waterTopCullStack = new int[WATER_TOP_CULL_CELL_COUNT];
    private final FluidTopOcclusion.CellClassifier waterTopCellClassifier =
            this::classifyWaterTopCellAtOffset;
    private final BlockState[] blockStateCache = new BlockState[LOCAL_BLOCK_COUNT];
    private final FluidState[] fluidStateCache = new FluidState[LOCAL_BLOCK_COUNT];

    private static final int FACE_CULL_CACHE_BITS = 13;
    private static final byte FACE_CULL_UNKNOWN = 0;
    private static final byte FACE_CULL_HIDDEN = 1;
    private static final byte FACE_CULL_DRAWN = 2;
    private final long[] faceCullKeys = new long[1 << FACE_CULL_CACHE_BITS];
    private final byte[] faceCullResults = new byte[1 << FACE_CULL_CACHE_BITS];

    private long modelRandomSeed = 42L;

    private boolean active;
    private BlockTextureManager texManager;
    private BlockRenderDispatcher blockRenderManager;
    private BlockColors blockColors;
    private int baseX, baseY, baseZ;
    private long topMask, bottomMask, eastMask, westMask, southMask, northMask;
    private final long[] opacity = new long[64];
    private final long[] blockStateKnown = new long[64];
    private final long[] fluidStateKnown = new long[64];
    private int blockCount, fluidCount;
    private int tintValidMask;
    private int waterColorKnownMask;
    private FluidState waterTopCellCacheFluid;
    private BlockAndTintGetter waterTopCullWorld;
    private FluidState waterTopCullReferenceFluid;
    private int waterTopCullOriginX, waterTopCullOriginY, waterTopCullOriginZ;

    private static final ResourceLocation WATER_STILL_SPRITE = new ResourceLocation("minecraft", "block/water_still");
    private static final ResourceLocation WATER_FLOW_SPRITE = new ResourceLocation("minecraft", "block/water_flow");

    private BlockQuadEmitter() {}

    public static BlockQuadEmitter threadLocal() {
        BlockQuadEmitter emitter = INSTANCE.get();
        if (emitter == null) {
            emitter = new BlockQuadEmitter();
            INSTANCE.set(emitter);
        }
        return emitter;
    }

    public static BlockQuadEmitter current() {
        return INSTANCE.get();
    }

    public static int getWaterTopOcclusionRadius() {
        return WATER_TOP_CULL_RADIUS;
    }

    public void beginSection(SectionPos sectionPos,
                             BlockTextureManager tex,
                             BlockRenderDispatcher brm) {
        if (active) {
            writer.releaseBuffers();
        }
        this.active = true;
        this.texManager = tex;
        this.blockRenderManager = brm;
        this.baseX = sectionPos.minBlockX();
        this.baseY = sectionPos.minBlockY();
        this.baseZ = sectionPos.minBlockZ();

        Minecraft client = Minecraft.getInstance();
        this.blockColors = (client != null) ? client.getBlockColors() : null;

        writer.beginSection(baseX, baseY, baseZ);
        lights.clear();
        topMask = bottomMask = eastMask = westMask = southMask = northMask = 0;
        Arrays.fill(opacity, 0);
        Arrays.fill(blockStateKnown, 0);
        Arrays.fill(fluidStateKnown, 0);
        Arrays.fill(faceCullResults, (byte) 0);
        waterTopCellCacheFluid = null;
        waterColorKnownMask = 0;
        blockCount = 0;
        fluidCount = 0;
    }

    public boolean isActive() { return active; }
    public int getBlockCount() { return blockCount; }
    public int getFluidCount() { return fluidCount; }

    public static RenderShape getRenderTypeCached(BlockState state) {
        int id = Block.getId(state);
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) {
            RenderShape renderType = cache[id];
            if (renderType != null) {
                return renderType;
            }
        }

        RenderShape renderType = state.getRenderShape();
        cache = ensureRenderTypeCacheCapacity(id);
        cache[id] = renderType;
        return renderType;
    }

    private static RenderShape[] ensureRenderTypeCacheCapacity(int id) {
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) return cache;
        synchronized (BlockQuadEmitter.class) {
            cache = renderTypeByStateId;
            if (id < cache.length) return cache;
            int newLength = Math.max(id + 1, cache.length * 2);
            RenderShape[] grown = Arrays.copyOf(cache, newLength);
            renderTypeByStateId = grown;
            return grown;
        }
    }

    public void processBlock(BlockState state, BlockGetter world, BlockPos pos, boolean opaque) {
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());
        int idx = localIndex(lx, ly, lz);
        cacheLocalBlockState(idx, state);

        if (opaque) {
            int word = idx >> 6;
            opacity[word] |= (1L << (idx & 63));
        } else {
            int subX = lx >> 1;
            int subY = ly >> 1;
            int subZ = lz >> 1;
            if (ly == 15) topMask    |= (1L << (subZ * 8 + subX));
            if (ly == 0)  bottomMask |= (1L << (subZ * 8 + subX));
            if (lx == 15) eastMask   |= (1L << (subZ * 8 + subY));
            if (lx == 0)  westMask   |= (1L << (subZ * 8 + subY));
            if (lz == 15) southMask  |= (1L << (subY * 8 + subX));
            if (lz == 0)  northMask  |= (1L << (subY * 8 + subX));
        }

        if (!BlockLightRuleManager.hasMatchingRules(state)) {
            return;
        }

        modelSampling.begin(blockRenderManager, state, state.getSeed(pos));
        List<LightEntry> resolvedLights = BlockLightRuleManager.resolve(state, pos, modelSampling);
        lights.addAll(resolvedLights);
    }

    public void emitBlock(BlockState state, BlockPos pos, BlockAndTintGetter world) {
        blockCount++;
        mutablePos.set(pos);
        tintValidMask = 0;

        modelRandomSeed = state.getSeed(pos);

        float lx = SectionPos.sectionRelative(pos.getX());
        float ly = SectionPos.sectionRelative(pos.getY());
        float lz = SectionPos.sectionRelative(pos.getZ());

        if (state.hasOffsetFunction()) {
            Vec3 modelOffset = state.getOffset(world, pos);
            lx += (float) modelOffset.x;
            ly += (float) modelOffset.y;
            lz += (float) modelOffset.z;
        }

        BakedModel model = blockRenderManager.getBlockModel(state);
        String layerName = classifyBlockLayer(state);

        emitVanillaBlock(world, state, model, layerName, lx, ly, lz);
    }

    public void emitFluid(BlockPos pos, BlockAndTintGetter world, FluidState fluidState) {
        fluidCount++;
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());
        cacheLocalFluidState(localIndex(lx, ly, lz), fluidState);
        emitFluidQuads(world, fluidState, lx, ly, lz);
    }

    public SectionBlockData finishSection(SectionPos sectionPos) {
        try (ProfileScope finishScope = ProfileScope.begin("finishSection")) {
            finishScope.blockCount(blockCount).fluidCount(fluidCount);
            SectionMeshData meshData = writer.buildMeshData();
            SectionBlockData result = new SectionBlockData(
                    sectionPos, meshData, List.copyOf(lights), topMask, bottomMask,
                    eastMask, westMask, southMask, northMask,
                    Arrays.copyOf(opacity, opacity.length));
            lights.clear();
            return result;
        } finally {
            active = false;
        }
    }

    private void emitVanillaBlock(BlockAndTintGetter world, BlockState state,
                                   BakedModel model, String layerName,
                                   float lx, float ly, float lz) {

        ModelData modelData = ModelData.EMPTY;
        net.minecraft.world.level.block.entity.BlockEntity be = world.getBlockEntity(mutablePos);
        if (be != null) {
            modelData = be.getModelData();
        }

        for (Direction dir : ALL_DIRECTIONS) {
            if (dir != null && !shouldDrawBlockSide(world, state, dir)) {
                continue;
            }

            random.setSeed(modelRandomSeed);
            List<BakedQuad> bakedQuads = model.getQuads(state, dir, random, modelData, null);
            if (bakedQuads != null && !bakedQuads.isEmpty()) {
                for (BakedQuad bq : bakedQuads) {
                    stageBakedQuad(bq, layerName, state, world, lx, ly, lz);
                }
            } else {
                for (RenderType rt : model.getRenderTypes(state, random, modelData)) {
                    List<BakedQuad> rtQuads = model.getQuads(state, dir, random, modelData, rt);
                    if (rtQuads != null && !rtQuads.isEmpty()) {
                        String rtLayer = layerFromRenderType(rt, layerName);
                        for (BakedQuad bq : rtQuads) {
                            stageBakedQuad(bq, rtLayer, state, world, lx, ly, lz);
                        }
                    }
                }
            }
        }

        writer.flushBlock(texManager);
    }

    private void stageBakedQuad(BakedQuad bq, String layerName,
                                 BlockState state, BlockAndTintGetter world,
                                 float lx, float ly, float lz) {
        int[] vd = bq.getVertices();

        for (int v = 0; v < 4; v++) {
            int off = v * 8;
            tempPositions[v * 3]     = Float.intBitsToFloat(vd[off])     + lx;
            tempPositions[v * 3 + 1] = Float.intBitsToFloat(vd[off + 1]) + ly;
            tempPositions[v * 3 + 2] = Float.intBitsToFloat(vd[off + 2]) + lz;
            tempUvs[v * 2]     = Float.intBitsToFloat(vd[off + 4]);
            tempUvs[v * 2 + 1] = Float.intBitsToFloat(vd[off + 5]);
        }

        int packedNormal = vd[7];
        float nx = (byte) (packedNormal & 0xFF) / 127f;
        float ny = (byte) ((packedNormal >> 8) & 0xFF) / 127f;
        float nz = (byte) ((packedNormal >> 16) & 0xFF) / 127f;
        if (nx * nx + ny * ny + nz * nz < 0.01f) {
            float e1x = tempPositions[3] - tempPositions[0], e1y = tempPositions[4] - tempPositions[1], e1z = tempPositions[5] - tempPositions[2];
            float e2x = tempPositions[6] - tempPositions[0], e2y = tempPositions[7] - tempPositions[1], e2z = tempPositions[8] - tempPositions[2];
            nx = e1y * e2z - e1z * e2y;
            ny = e1z * e2x - e1x * e2z;
            nz = e1x * e2y - e1y * e2x;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0.001f) { nx /= len; ny /= len; nz /= len; }
        }

        int color = 0xFFFFFFFF;
        if (bq.getTintIndex() >= 0 && blockColors != null) {
            int tint = getBlockTint(state, world, bq.getTintIndex());
            int r = (tint >> 16) & 0xFF, g = (tint >> 8) & 0xFF, b = tint & 0xFF;
            color = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        TextureAtlasSprite sprite = bq.getSprite();
        ResourceLocation spriteId = sprite != null ? sprite.contents().name() : null;
        if (isWaterSprite(spriteId)) {
            emitBlockWaterQuad(nx, ny, nz, color, layerName);
            return;
        }
        float su0 = sprite != null ? sprite.getU0() : 0;
        float sv0 = sprite != null ? sprite.getV0() : 0;
        float su1 = sprite != null ? sprite.getU1() : 1;
        float sv1 = sprite != null ? sprite.getV1() : 1;

        writer.stageQuad(tempPositions, tempUvs, nx, ny, nz, color,
                spriteId, su0, sv0, su1, sv1, layerName);
    }

    static boolean isWaterSprite(ResourceLocation spriteId) {
        return WATER_STILL_SPRITE.equals(spriteId) || WATER_FLOW_SPRITE.equals(spriteId);
    }

    private void emitBlockWaterQuad(float nx, float ny, float nz, int color, String layerName) {
        float tileSize = BlockTextureManager.getWaterTileSize();
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        for (int v = 0; v < 4; v++) {
            float wx = baseX + tempPositions[v * 3];
            float wy = baseY + tempPositions[v * 3 + 1];
            float wz = baseZ + tempPositions[v * 3 + 2];
            float u, w;
            if (ay >= ax && ay >= az) {
                u = wx; w = wz;
            } else if (ax >= az) {
                u = wz; w = wy;
            } else {
                u = wx; w = wy;
            }
            tempUvs[v * 2] = u / tileSize;
            tempUvs[v * 2 + 1] = w / tileSize;
        }
        writer.writeFluidQuad(tempPositions, tempUvs, nx, ny, nz, color,
                null, 0, 0, 1, 1, layerName, texManager);
    }

    private void emitFluidQuads(BlockAndTintGetter world, FluidState fluidState,
                                 int lx, int ly, int lz) {
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluidState);
        fluidPos.set(baseX + lx, baseY + ly, baseZ + lz);

        ResourceLocation stillId = ext.getStillTexture(fluidState, world, fluidPos);
        ResourceLocation flowId = ext.getFlowingTexture(fluidState, world, fluidPos);
        if (stillId == null || flowId == null) return;

        Minecraft mc = Minecraft.getInstance();
        TextureAtlasSprite stillSprite = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(stillId);
        TextureAtlasSprite flowSprite = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(flowId);

        boolean isLava = fluidState.is(FluidTags.LAVA);
        boolean isWater = !isLava;
        String layer = isLava ? "solid" : "translucent";
        int fluidColor = isWater
                ? getCachedWaterColor(ext, world, fluidState, lx, ly, lz)
                : ext.getTintColor(fluidState, world, fluidPos);
        int r = (fluidColor >> 16) & 0xFF, g = (fluidColor >> 8) & 0xFF, b = fluidColor & 0xFF;
        int color = 0xFF000000 | (r << 16) | (g << 8) | b;

        float h00 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz);
        float h10 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz);
        float h01 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz + 1);
        float h11 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz + 1);

        float tileSize = BlockTextureManager.getWaterTileSize();
        int wx = baseX + lx, wy = baseY + ly, wz = baseZ + lz;

        float su0 = stillSprite.getU0(), sv0 = stillSprite.getV0();
        float su1 = stillSprite.getU1(), sv1 = stillSprite.getV1();
        float fu0 = flowSprite.getU0(), fv0 = flowSprite.getV0();
        float fu1 = flowSprite.getU1(), fv1 = flowSprite.getV1();

        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() + 1, fluidPos.getZ());
        FluidState aboveFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        boolean submerged = aboveFluid.getType().isSame(fluidState.getType());
        boolean waterTopCovered = false;
        if (isWater && !submerged && fluidState.isSource()) {
            BlockState aboveState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            float topCoverageHeight = Math.min(Math.min(h00, h10), Math.min(h01, h11));
            if (isFluidTopCoveredByCeiling(world, topCoverageHeight, fluidCheckPos, aboveState)) {
                waterTopCovered = classifyWaterTopExposure(world, fluidState, wx, wy, wz)
                        == FluidTopOcclusion.Exposure.HIDDEN;
            }
        }

        // Top face
        if (!submerged && !waterTopCovered) {
            setPos4(tempPositions, lx, ly + h00, lz, lx + 1, ly + h10, lz,
                    lx + 1, ly + h11, lz + 1, lx, ly + h01, lz + 1);
            if (isWater) {
                float u0 = wx / tileSize, v0 = wz / tileSize;
                float u1 = (wx + 1) / tileSize, v1 = (wz + 1) / tileSize;
                setUv4(tempUvs, u0, v0, u1, v0, u1, v1, u0, v1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
                setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                        lx + 1, ly + h10, lz, lx, ly + h00, lz);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                Vec3 flow = fluidState.getFlow(world, fluidPos);
                if (flow.x == 0.0 && flow.z == 0.0) {
                    setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                } else {
                    float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2.0);
                    float sin = Mth.sin(angle) * 0.25f;
                    float cos = Mth.cos(angle) * 0.25f;
                    float c00u = flowSprite.getU(0.5f - cos - sin), c00v = flowSprite.getV(0.5f - cos + sin);
                    float c01u = flowSprite.getU(0.5f - cos + sin), c01v = flowSprite.getV(0.5f + cos + sin);
                    float c11u = flowSprite.getU(0.5f + cos + sin), c11v = flowSprite.getV(0.5f + cos - sin);
                    float c10u = flowSprite.getU(0.5f + cos - sin), c10v = flowSprite.getV(0.5f - cos - sin);
                    setUv4(tempUvs, c00u, c00v, c10u, c10v, c11u, c11v, c01u, c01v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    setUv4(tempUvs, c01u, c01v, c11u, c11v, c10u, c10v, c00u, c00v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                }
            }
        }

        // Bottom face
        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() - 1, fluidPos.getZ());
        FluidState belowFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        BlockState belowState = getCachedBlockState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        if (!belowFluid.getType().isSame(fluidState.getType()) && !belowState.isSolidRender(world, fluidCheckPos)) {
            setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                    lx + 1, ly, lz, lx, ly, lz);
            if (isWater) {
                float u0 = wx / tileSize, v0 = wz / tileSize;
                float u1 = (wx + 1) / tileSize, v1 = (wz + 1) / tileSize;
                setUv4(tempUvs, u0, v1, u1, v1, u1, v0, u0, v0);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        stillId, su0, sv0, su1, sv1, layer, texManager);
            }
        }

        // Side faces
        for (Direction dir : HORIZONTAL_DIRECTIONS) {
            fluidCheckPos.set(
                fluidPos.getX() + dir.getStepX(),
                fluidPos.getY(),
                fluidPos.getZ() + dir.getStepZ());
            FluidState sideFluid = getCachedFluidState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            BlockState sideState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());

            if (sideFluid.getType().isSame(fluidState.getType())) continue;
            if (sideState.isSolidRender(world, fluidCheckPos)) continue;

            float sideH0, sideH1;
            switch (dir) {
                case NORTH -> {
                    sideH0 = h10; sideH1 = h00;
                    setPos4(tempPositions, lx + 1, ly, lz, lx, ly, lz,
                            lx, ly + sideH1, lz, lx + 1, ly + sideH0, lz);
                }
                case SOUTH -> {
                    sideH0 = h01; sideH1 = h11;
                    setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                            lx + 1, ly + sideH1, lz + 1, lx, ly + sideH0, lz + 1);
                }
                case EAST -> {
                    sideH0 = h11; sideH1 = h10;
                    setPos4(tempPositions, lx + 1, ly, lz + 1, lx + 1, ly, lz,
                            lx + 1, ly + sideH1, lz, lx + 1, ly + sideH0, lz + 1);
                }
                case WEST -> {
                    sideH0 = h00; sideH1 = h01;
                    setPos4(tempPositions, lx, ly, lz, lx, ly, lz + 1,
                            lx, ly + sideH1, lz + 1, lx, ly + sideH0, lz);
                }
                default -> { continue; }
            }

            float fnx = dir.getStepX(), fny = 0, fnz = dir.getStepZ();
            if (isWater) {
                boolean xAxis = (dir == Direction.EAST || dir == Direction.WEST);
                float sU0, sU1, sV0, sV1;
                if (xAxis) {
                    sU0 = wz / tileSize; sU1 = (wz + 1) / tileSize;
                } else {
                    sU0 = wx / tileSize; sU1 = (wx + 1) / tileSize;
                }
                sV0 = wy / tileSize; sV1 = (wy + 1) / tileSize;
                setUv4(tempUvs, sU0, sV1, sU1, sV1, sU1, sV0, sU0, sV0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                float sideU0 = flowSprite.getU(0.0f);
                float sideU1 = flowSprite.getU(0.5f);
                float sideVBottom = flowSprite.getV(0.5f);
                float sideVTop0 = flowSprite.getV((1.0f - sideH0) * 0.5f);
                float sideVTop1 = flowSprite.getV((1.0f - sideH1) * 0.5f);
                setUv4(tempUvs, sideU0, sideVBottom, sideU1, sideVBottom, sideU1, sideVTop1, sideU0, sideVTop0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        flowId, fu0, fv0, fu1, fv1, layer, texManager);
            }
        }
    }

    private static void setPos4(float[] p, float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3) {
        p[0] = x0; p[1] = y0; p[2] = z0;
        p[3] = x1; p[4] = y1; p[5] = z1;
        p[6] = x2; p[7] = y2; p[8] = z2;
        p[9] = x3; p[10] = y3; p[11] = z3;
    }

    private static void setUv4(float[] uvs, float u0, float v0,
                               float u1, float v1,
                               float u2, float v2,
                               float u3, float v3) {
        uvs[0] = u0; uvs[1] = v0;
        uvs[2] = u1; uvs[3] = v1;
        uvs[4] = u2; uvs[5] = v2;
        uvs[6] = u3; uvs[7] = v3;
    }

    private static boolean isFluidTopCoveredByCeiling(BlockGetter world, float fluidHeight,
                                                      BlockPos neighborPos, BlockState neighborState) {
        if (fluidHeight + WATER_HEIGHT_EPSILON < WATER_CEILING_CULL_MIN_HEIGHT
                || !neighborState.canOcclude()) {
            return false;
        }
        return neighborState.isSolidRender(world, neighborPos);
    }

    private FluidTopOcclusion.Exposure classifyWaterTopExposure(BlockAndTintGetter world,
                                                                FluidState fluidState,
                                                                int originX, int originY, int originZ) {
        if (waterTopCellCacheFluid != fluidState) {
            Arrays.fill(waterTopCellCache, FluidTopOcclusion.UNKNOWN);
            waterTopCellCacheFluid = fluidState;
        }
        waterTopCullWorld = world;
        waterTopCullReferenceFluid = fluidState;
        waterTopCullOriginX = originX;
        waterTopCullOriginY = originY;
        waterTopCullOriginZ = originZ;
        try {
            return FluidTopOcclusion.classifyConnectedOrigin(
                    WATER_TOP_CULL_RADIUS, waterTopCullStack, waterTopCellClassifier);
        } finally {
            waterTopCullWorld = null;
            waterTopCullReferenceFluid = null;
        }
    }

    private byte classifyWaterTopCellAtOffset(int xOffset, int zOffset) {
        int worldX = waterTopCullOriginX + xOffset;
        int worldY = waterTopCullOriginY;
        int worldZ = waterTopCullOriginZ + zOffset;
        int cacheIndex = waterTopCellCacheIndex(worldX, worldY, worldZ);
        if (cacheIndex < 0) {
            return classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
        }

        byte classification = waterTopCellCache[cacheIndex];
        if (classification == FluidTopOcclusion.UNKNOWN) {
            classification = classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
            waterTopCellCache[cacheIndex] = classification;
        }
        return classification;
    }

    private int waterTopCellCacheIndex(int worldX, int worldY, int worldZ) {
        int x = worldX - baseX + WATER_TOP_CULL_RADIUS;
        int y = worldY - baseY;
        int z = worldZ - baseZ + WATER_TOP_CULL_RADIUS;
        if ((x | y | z) < 0
                || x >= WATER_TOP_CACHE_WIDTH
                || y >= SECTION_SIZE
                || z >= WATER_TOP_CACHE_WIDTH) {
            return -1;
        }
        return (y * WATER_TOP_CACHE_WIDTH + z) * WATER_TOP_CACHE_WIDTH + x;
    }

    private byte classifyWaterTopCell(BlockAndTintGetter world, FluidState referenceFluid,
                                      int worldX, int worldY, int worldZ) {
        BlockState state = getCachedBlockState(world, worldX, worldY, worldZ);
        if (state.canOcclude()) {
            return FluidTopOcclusion.BLOCKED;
        }

        FluidState fluid = getCachedFluidState(world, worldX, worldY, worldZ);
        if (!fluid.getType().isSame(referenceFluid.getType()) || !fluid.isSource()) {
            return FluidTopOcclusion.EXPOSED;
        }

        FluidState aboveFluid = getCachedFluidState(world, worldX, worldY + 1, worldZ);
        if (aboveFluid.getType().isSame(referenceFluid.getType())) {
            return FluidTopOcclusion.BLOCKED;
        }

        fluidCheckPos.set(worldX, worldY + 1, worldZ);
        BlockState aboveState = getCachedBlockState(world, worldX, worldY + 1, worldZ);
        return isFluidTopCoveredByCeiling(world, fluid.getOwnHeight(), fluidCheckPos, aboveState)
                ? FluidTopOcclusion.CONNECTED
                : FluidTopOcclusion.EXPOSED;
    }

    private float getFluidHeight(BlockAndTintGetter world, FluidState referenceFluid,
                                  int worldX, int worldY, int worldZ) {
        float totalHeight = 0;
        int count = 0;

        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                fluidCheckPos.set(worldX + dx, worldY + 1, worldZ + dz);
                FluidState aboveFluid = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (aboveFluid.getType().isSame(referenceFluid.getType())) {
                    return 1.0f;
                }

                fluidCheckPos.set(worldX + dx, worldY, worldZ + dz);
                FluidState fs = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (fs.getType().isSame(referenceFluid.getType())) {
                    float h = fs.getOwnHeight();
                    totalHeight += h;
                    count++;
                } else if (!getCachedBlockState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ()).canOcclude()) {
                    count++;
                }
            }
        }

        return count > 0 ? totalHeight / count : 0.5f;
    }

    private int getCachedWaterColor(IClientFluidTypeExtensions ext, BlockAndTintGetter world,
                                    FluidState fluidState, int lx, int ly, int lz) {
        int cell = ((ly >> 3) << 4) | ((lz >> 2) << 2) | (lx >> 2);
        int bit = 1 << cell;
        if ((waterColorKnownMask & bit) == 0) {
            waterColorCache[cell] = ext.getTintColor(fluidState, world, fluidPos);
            waterColorKnownMask |= bit;
        }
        return waterColorCache[cell];
    }

    private boolean shouldDrawBlockSide(BlockAndTintGetter world, BlockState state, Direction side) {
        int neighborX = mutablePos.getX() + side.getStepX();
        int neighborY = mutablePos.getY() + side.getStepY();
        int neighborZ = mutablePos.getZ() + side.getStepZ();
        BlockState neighbor = getCachedBlockState(world, neighborX, neighborY, neighborZ);
        long key = ((long) Block.getId(state) << 35)
                | ((long) Block.getId(neighbor) << 3)
                | side.ordinal();
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - FACE_CULL_CACHE_BITS));
        if (faceCullKeys[slot] == key && faceCullResults[slot] != FACE_CULL_UNKNOWN) {
            return faceCullResults[slot] == FACE_CULL_DRAWN;
        }

        neighborPos.set(neighborX, neighborY, neighborZ);
        boolean drawn = Block.shouldRenderFace(state, world, mutablePos, side, neighborPos);
        faceCullKeys[slot] = key;
        faceCullResults[slot] = drawn ? FACE_CULL_DRAWN : FACE_CULL_HIDDEN;
        return drawn;
    }

    private BlockState getCachedBlockState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getBlockState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((blockStateKnown[word] & bit) != 0) {
            return blockStateCache[idx];
        }

        neighborPos.set(worldX, worldY, worldZ);
        BlockState state = world.getBlockState(neighborPos);
        cacheLocalBlockState(idx, state);
        return state;
    }

    private FluidState getCachedFluidState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getFluidState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((fluidStateKnown[word] & bit) != 0) {
            return fluidStateCache[idx];
        }

        neighborPos.set(worldX, worldY, worldZ);
        FluidState state = world.getFluidState(neighborPos);
        cacheLocalFluidState(idx, state);
        return state;
    }

    private void cacheLocalBlockState(int idx, BlockState state) {
        blockStateCache[idx] = state;
        blockStateKnown[idx >> 6] |= (1L << (idx & 63));
    }

    private void cacheLocalFluidState(int idx, FluidState state) {
        fluidStateCache[idx] = state;
        fluidStateKnown[idx >> 6] |= (1L << (idx & 63));
    }

    private int getBlockTint(BlockState state, BlockAndTintGetter world, int tintIndex) {
        if (tintIndex < 0 || tintIndex >= TINT_CACHE_SIZE) {
            return blockColors.getColor(state, world, mutablePos, tintIndex);
        }
        int bit = 1 << tintIndex;
        if ((tintValidMask & bit) == 0) {
            tintCache[tintIndex] = blockColors.getColor(state, world, mutablePos, tintIndex);
            tintValidMask |= bit;
        }
        return tintCache[tintIndex];
    }

    public static String classifyBlockLayer(BlockState state) {
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) return "glass";
        RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
        if (layer == RenderType.solid()) return "solid";
        if (layer == RenderType.cutoutMipped()) return "cutout_mipped";
        if (layer == RenderType.cutout()) return "cutout";
        if (layer == RenderType.translucent()) return "translucent";
        if (layer == RenderType.tripwire()) return "cutout";
        return "solid";
    }

    private static String layerFromRenderType(RenderType rt, String fallback) {
        if (rt == RenderType.solid()) return "solid";
        if (rt == RenderType.cutoutMipped()) return "cutout_mipped";
        if (rt == RenderType.cutout()) return "cutout";
        if (rt == RenderType.translucent()) return "translucent";
        if (rt == RenderType.tripwire()) return "cutout";
        return fallback;
    }

    private static int localIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }
}
