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
- **T2 (Phase 0)** — S1-S4; inline; **in_progress**; Setup isolated `forge-1.20.1/` module with Gradle 8.4 wrapper, ForgeGradle 6.0.+, Java 17 toolchain, shared `rtxremix-java/src` linkage, and packaging tasks.
- **T3 (Phase 1)** — S1-S2; inline; **pending**; Implement 100% Client-Side bootstrap (`@Mod("rtxremix")`, `DisplayTest`), `ForgeNativeExtractor`, and window swapchain takeover.
- **T4 (Phase 2)** — S1-S3; inline; **pending**; Implement Terrain capture for 1.20.1 (`SectionBuilderMixin`, `ModelData`), SkyRenderer underground suppression, and `TextureOverlay` 2D GUI pass.
- **T5 (Phase 3)** — S1-S3; inline; **pending**; Implement Entity capture with expanded vertex limit (16k), GeckoLib/Citadel static mesh hooks, and Alex's Caves volumetric froxel mapping.
- **T6 (Phase 4)** — S1-S4; inline; **pending**; Build distribution package (`packageForge`), run verification tests, and validate artifact integrity.

## Log

- **L1..L13** — Historical setup and context alignment.
- **L14** — Full compatibility audit completed by fleet across HUD, Entities (GeckoLib/Citadel), Terrain (Dynamic Trees), and Build architecture. Results persisted in Engram (#2011, #2012, #2013, #2014, #2015, #2016, #2017, #2018).
- **L15** — User initiated `/goal` for complete autonomous execution of the Forge 1.20.1 port.
