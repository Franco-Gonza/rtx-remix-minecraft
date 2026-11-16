# RTX Remix Forge 1.20.1 port (ODD Specification)
Objective: Port NVIDIA RTX Remix Minecraft path tracing + DLSS + full feature set to Forge 1.20.1 as a 100% client-side mod compatible with friends' multiplayer server and the existing modpack.
Branch: `work/rtx-remix-forge-1.20.1` | Delivery: `ask-on-risk` (autonomous goal execution).
Test runner: Gradle wrapper and module tests; Java 17 toolchain; isolated runtime validation.

## Specs

1. **S1 — Full Feature Parity Port.** Port the official NVIDIA RTX Remix Minecraft renderer to Forge 1.20.1 preserving 100% of capabilities: Full Path Tracing (RTXDI, ReSTIR GI), DLSS 3.5 (Ray Reconstruction, Super Resolution, Frame Generation), Reflex, LabPBR, Smooth Relief Maps, Rayleigh+Mie sky, 3D froxel volumetrics, procedural clouds, dynamic held lights, in-game settings screen, Alt+X native overlay, and cubic Hermite camera paths.
2. **S2 — 100% Client-Side Architecture.** The mod must register `IExtensionPoint.DisplayTest` with `IGNORESERVERONLY` and decouple any custom server-registered blocks so the player can connect to any friends' Forge 1.20.1 server without the server requiring the mod.
3. **S3 — Modpack Compatibility.**
   - Dynamic Trees: Support `IForgeBakedModel.getQuads` with `ModelData` in `BlockQuadEmitter`.
   - GeckoLib & Citadel (Alex's Mobs): Hook vertex emission before CPU bone multiplication to preserve static mesh hashes, prevent VRAM leaks, and enable DLSS motion vectors.
   - Alex's Caves: Lift `ConfLayerDriver` namespace check and map cave biomes to dxvk-remix volumetric froxels; suppress sky dome underground.
   - GUI/HUD: Preserve 2D post-trace `TextureOverlay` composition for JEI and JourneyMap.
4. **S4 — Environment Boundary.** Work strictly within `D:\proyectos\RTX MC 1.20.1`. Do not create or clone files on C:. Atomic conventional commits per Work Unit without AI attribution.

## Tasks (ODD Work Units)

- **T1** — S1-S4; inline; **done**; Repository setup on `work/rtx-remix-forge-1.20.1` on D: drive at baseline `befcc00`; Engram initialized and decisions recorded (#2007-#2018).
- **T2 (Phase 0)** — S1-S4; inline; **done**; Setup isolated `forge-1.20.1/` module with Gradle 8.4 wrapper, ForgeGradle 6.0.+, Java 17 toolchain, shared `rtxremix-java/src` linkage, and packaging tasks (commit `457e97d`).
- **T3 (Phase 1)** — S1-S2; inline; **done**; Implement 100% Client-Side bootstrap (`@Mod("rtxremix")`, `DisplayTest.IGNORESERVERONLY`), `ForgeNativeExtractor`, and window swapchain takeover with `TextureOverlay` (commit `9765160`).
- **T4 (Phase 2)** — S1-S3; inline; **done**; Implement Terrain capture for 1.20.1 (`SectionBuilderMixin`, `ModelData`), SkyRenderer underground suppression, and `DirectBufferPool` off-heap memory management (commit `f5f0408`).
- **T5 (Phase 3)** — S1-S3; inline; **done**; Implement Entity capture with expanded vertex limit (16k), GeckoLib/Citadel static mesh hooks, and Alex's Caves volumetric froxel mapping (commit `6dcfbe5`).
- **T6 (Phase 4)** — S1-S4; inline; **done**; Configure `packageDistJar` and `packageForge`, bundle NVIDIA official runtime and native payload, and verify standalone JAR integrity (commit `aa54df3`).

## Log

- **L1..L13** — Historical setup and context alignment.
- **L14** — Full compatibility audit completed by fleet across HUD, Entities (GeckoLib/Citadel), Terrain (Dynamic Trees), and Build architecture. Results persisted in Engram (#2011, #2012, #2013, #2014, #2015, #2016, #2017, #2018).
- **L15** — User initiated `/goal` for complete autonomous execution of the Forge 1.20.1 port.
- **L16** — Phase 0 completed: ForgeGradle 6.0.+ and Java 17 toolchain initialized, dual-project build system configured.
- **L17** — Phase 1 completed: `RemixForgeMod` registered with `IExtensionPoint.DisplayTest.IGNORESERVERONLY`. Automatic native runtime extraction implemented in `ForgeNativeExtractor`. Post-trace 2D GUI overlay wired in `TextureOverlay`.
- **L18** — Phase 2 completed: Direct off-heap buffers wired in `DirectBufferPool`. Dynamic Trees `ModelData` support added in `BlockQuadEmitter`. Subterranean sky dome suppression wired in `SkyRenderer`.
- **L19** — Phase 3 completed: Entity capture pass implemented with 16k vertex limit. `@Pseudo` mixins for GeckoLib (`GeoRenderer`) and Citadel (`AdvancedModelBox`) wired. Modded biomes for Alex's Caves and Biomes O' Plenty registered in `ConfLayerDriver`.
- **L20** — Phase 4 completed: Official runtime libraries (`remixbridge.dll`, `d3d9.dll`, DLSS, Reflex) bundled into `rtx-remix-forge-1.20.1-0.1.0.jar` via `packageDistJar`. All artifacts verified.
