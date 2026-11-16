/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoSphereEXT;
import com.nvidia.rtxremix.RemixAPI;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Manages dynamic path-traced sphere lights for held and dropped light-emitting items
 * (torches, lanterns, soul flames, glowstone, sea pickles, modded light sources).
 *
 * <p>Invariant: Local first-person held lights are submitted with volumetricRadianceScale = 0
 * to prevent smearing in RTX Remix's volumetric accumulation grid.
 */
public final class HeldLightManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/HeldLightManager");

    private static final long HELD_LIGHT_HASH_BASE = 0x4845_4C44_0000_0000L; // "HELD"
    private static final float LIGHT_RADIUS = 0.20f;
    private static final float RADIANCE_SCALE = 75.0f;

    private static boolean enabled = true;
    private static boolean localHeldLightsOff = false;

    private final Map<Long, Long> activeLights = new HashMap<>();
    private final Set<Long> seenKeys = new HashSet<>();

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isLocalHeldLightsOff() {
        return localHeldLightsOff;
    }

    public static void toggleLocalHeldLights() {
        localHeldLightsOff = !localHeldLightsOff;
    }

    public void update(RemixAPI api, ClientLevel level, Camera camera, float tickDelta) {
        if (!enabled || api == null || level == null || camera == null) {
            return;
        }

        seenKeys.clear();
        Minecraft mc = Minecraft.getInstance();
        Entity cameraEntity = camera.getEntity();
        Vec3 camPos = camera.getPosition();
        boolean isFirstPerson = mc.options != null && !mc.options.getCameraType().isMirrored()
                && mc.options.getCameraType().isFirstPerson();

        Vec3 look = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        if (right.lengthSqr() < 0.001) {
            right = new Vec3(1, 0, 0);
        }
        Vec3 up = right.cross(look).normalize();

        for (Entity entity : level.entitiesForRendering()) {
            if (entity == null || !entity.isAlive()) continue;

            if (entity instanceof ItemEntity itemEntity) {
                if (entity.distanceToSqr(camPos) > 48.0 * 48.0) continue;
                ItemStack stack = itemEntity.getItem();
                LightColorEmission lce = evaluateItemLight(stack);
                if (lce != null) {
                    boolean underwater = entity.isInWater() || entity.isUnderWater();
                    if (!underwater || lce.burnsUnderwater) {
                        long key = HELD_LIGHT_HASH_BASE ^ (((long) entity.getId()) << 16);
                        Vec3 pos = entity.getPosition(tickDelta).add(0, 0.2, 0);
                        updateOrAddLight(api, key, pos, lce, 1.0f);
                    }
                }
            } else if (entity instanceof LivingEntity living) {
                if (entity.distanceToSqr(camPos) > 64.0 * 64.0) continue;
                boolean isLocalPlayer = living == cameraEntity;

                // Main hand
                updateSlotLight(api, living, EquipmentSlot.MAINHAND, isLocalPlayer, isFirstPerson,
                        camPos, look, right, up, tickDelta);

                // Off hand
                updateSlotLight(api, living, EquipmentSlot.OFFHAND, isLocalPlayer, isFirstPerson,
                        camPos, look, right, up, tickDelta);
            }
        }

        // Destroy lights that were unequipped or despawned this frame
        Iterator<Map.Entry<Long, Long>> it = activeLights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> entry = it.next();
            if (!seenKeys.contains(entry.getKey())) {
                long handle = entry.getValue();
                if (handle != 0) {
                    try {
                        api.destroyLight(handle);
                    } catch (Exception ignored) {}
                }
                it.remove();
            }
        }
    }

    private void updateSlotLight(RemixAPI api, LivingEntity living, EquipmentSlot slot,
                                 boolean isLocalPlayer, boolean isFirstPerson,
                                 Vec3 camPos, Vec3 look, Vec3 right, Vec3 up, float tickDelta) {
        if (isLocalPlayer && localHeldLightsOff) {
            return;
        }

        ItemStack stack = living.getItemBySlot(slot);
        LightColorEmission lce = evaluateItemLight(stack);
        if (lce == null) return;

        boolean underwater = living.isEyeInFluidType(net.minecraftforge.common.ForgeMod.WATER_TYPE.get()) || living.isUnderWater();
        if (underwater && !lce.burnsUnderwater) {
            return;
        }

        int slotIdx = slot == EquipmentSlot.MAINHAND ? 1 : 2;
        long key = HELD_LIGHT_HASH_BASE ^ (((long) living.getId()) << 16) ^ slotIdx;

        Vec3 lightPos;
        float volumetricScale;

        if (isLocalPlayer && isFirstPerson) {
            double lateralOffset = (slot == EquipmentSlot.MAINHAND) ? 0.28 : -0.28;
            lightPos = camPos.add(look.scale(0.38))
                    .add(right.scale(lateralOffset))
                    .add(up.scale(-0.20));
            // Invariant: 0 volumetric radiance for local first-person lights
            volumetricScale = 0.0f;
        } else {
            double lateralOffset = (slot == EquipmentSlot.MAINHAND) ? 0.35 : -0.35;
            Vec3 entityLook = Vec3.directionFromRotation(living.getXRot(), living.getYRot());
            Vec3 entityRight = entityLook.cross(new Vec3(0, 1, 0)).normalize();
            if (entityRight.lengthSqr() < 0.001) entityRight = right;

            lightPos = living.getPosition(tickDelta)
                    .add(0, living.getEyeHeight() * 0.65, 0)
                    .add(entityLook.scale(0.25))
                    .add(entityRight.scale(lateralOffset));
            volumetricScale = 1.0f;
        }

        updateOrAddLight(api, key, lightPos, lce, volumetricScale);
    }

    private void updateOrAddLight(RemixAPI api, long key, Vec3 pos, LightColorEmission lce, float volumetricScale) {
        seenKeys.add(key);

        float intensity = lce.emission * RADIANCE_SCALE;
        float radR = lce.r * intensity;
        float radG = lce.g * intensity;
        float radB = lce.b * intensity;

        LightInfoSphereEXT sphere = LightInfoSphereEXT.builder()
                .position((float) pos.x, (float) pos.y, (float) pos.z)
                .radius(LIGHT_RADIUS)
                .volumetricRadianceScale(volumetricScale)
                .build();

        LightInfo info = LightInfo.builder()
                .hash(key)
                .radiance(radR, radG, radB)
                .sphereExt(sphere)
                .build();

        try {
            Long oldHandle = activeLights.get(key);
            long handle = api.createLight(info);
            if (handle != 0) {
                if (oldHandle != null && oldHandle != 0 && oldHandle != handle) {
                    try { api.destroyLight(oldHandle); } catch (Exception ignored) {}
                }
                activeLights.put(key, handle);
                api.drawLightInstance(handle);
            }
        } catch (Exception e) {
            LOGGER.debug("Held light update failed for key 0x{}: {}", Long.toHexString(key), e.getMessage());
        }
    }

    public void drawLights(RemixAPI api) {
        if (!enabled || api == null) return;
        for (Long handle : activeLights.values()) {
            if (handle != null && handle != 0) {
                try {
                    api.drawLightInstance(handle);
                } catch (Exception ignored) {}
            }
        }
    }

    public void clear(RemixAPI api) {
        for (Long handle : activeLights.values()) {
            if (handle != null && handle != 0 && api != null) {
                try {
                    api.destroyLight(handle);
                } catch (Exception ignored) {}
            }
        }
        activeLights.clear();
        seenKeys.clear();
    }

    private static LightColorEmission evaluateItemLight(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return null;
        String path = id.getPath();

        // 1. Check soul items (blue flame, waterproof)
        if (path.contains("soul_torch") || path.contains("soul_lantern") || path.contains("soul_campfire")) {
            return new LightColorEmission(12, 0.35f, 0.70f, 1.0f, true);
        }

        // 2. Redstone items (dim deep red)
        if (path.contains("redstone_torch")) {
            return new LightColorEmission(7, 1.0f, 0.15f, 0.05f, false);
        }

        // 3. Lanterns (warm golden, sealed glass -> burns underwater)
        if (path.contains("lantern") && !path.contains("jack_o_lantern")) {
            return new LightColorEmission(15, 1.0f, 0.85f, 0.50f, true);
        }

        // 4. Sea lanterns and sea pickles (cool white/cyan, underwater)
        if (path.contains("sea_lantern") || path.contains("sea_pickle")) {
            return new LightColorEmission(15, 0.80f, 0.95f, 1.0f, true);
        }

        // 5. Glowstone / shroomlight / froglight / magma block (warm amber, waterproof)
        if (path.contains("glowstone") || path.contains("shroomlight") || path.contains("froglight") || path.contains("magma")) {
            return new LightColorEmission(15, 1.0f, 0.85f, 0.60f, true);
        }

        // 6. Normal torches and campfires (open flame, warm orange, extinguished underwater)
        if (path.contains("torch") || path.contains("campfire") || path.contains("jack_o_lantern")) {
            return new LightColorEmission(14, 1.0f, 0.75f, 0.35f, false);
        }

        // 7. Lava buckets (liquid incandescent rock, intense warm orange, burns underwater)
        if (path.contains("lava_bucket")) {
            return new LightColorEmission(15, 1.0f, 0.50f, 0.10f, true);
        }

        // 8. Glow ink, glow berries and glowing sacs
        if (path.contains("glow_ink") || path.contains("glow_berries") || path.contains("glow_item_frame")) {
            return new LightColorEmission(9, 0.30f, 0.85f, 0.90f, true);
        }

        // 9. Alex's Caves radioactive and toxic glow
        if (path.contains("uranium") || path.contains("radrock") || path.contains("fissile_core") || path.contains("nuclear")) {
            return new LightColorEmission(14, 0.20f, 1.0f, 0.25f, true);
        }
        if (path.contains("bioluminescent_torch") || path.contains("pewen_sap")) {
            return new LightColorEmission(14, 0.15f, 0.85f, 1.0f, true);
        }
        if (path.contains("acid_bucket") || path.contains("toxic_waste")) {
            return new LightColorEmission(12, 0.45f, 1.0f, 0.15f, true);
        }

        // 10. General BlockItem light emission fallback (e.g. modded glowing blocks)
        if (stack.getItem() instanceof BlockItem bi) {
            Block block = bi.getBlock();
            int emission = block.defaultBlockState().getLightEmission();
            if (emission > 0) {
                return new LightColorEmission(emission, 1.0f, 0.90f, 0.75f, false);
            }
        }

        return null;
    }

    private static final class LightColorEmission {
        final int emission;
        final float r, g, b;
        final boolean burnsUnderwater;

        LightColorEmission(int emission, float r, float g, float b, boolean burnsUnderwater) {
            this.emission = emission;
            this.r = r;
            this.g = g;
            this.b = b;
            this.burnsUnderwater = burnsUnderwater;
        }
    }
}
