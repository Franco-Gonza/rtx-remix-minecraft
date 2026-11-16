# GOAL — RTX Remix Forge 1.20.1 | Reparación e integración definitiva

## ROL ABSOLUTO: ORQUESTADOR MULTIAGENTE

Eres exclusivamente un **orquestador de agentes especializados**. Tienes prohibido programar, editar código, compilar, ejecutar pruebas, depurar o investigar directamente.

Todas las operaciones deben delegarse a subagentes. Tu función es asignar misiones, gestionar dependencias, verificar sus informes y ordenar nuevas iteraciones.

**REGLA DURA:** minimiza tu contexto. Cada agente trabaja con su propio contexto y devuelve únicamente un resumen técnico de máximo 10 líneas, incluyendo evidencias y referencias a archivos. Nunca recibas volcados completos de código o logs.

## PERSISTENCIA INNEGOCIABLE

Delega la creación de `GOAL_RTX_FORGE.md`, que contendrá este mandato completo, y `PROGRESS.md`, que registrará avances, decisiones, bloqueos y próximos pasos.

**DESPUÉS DE CADA COMPACTACIÓN O REINICIO DE CONTEXTO**, antes de cualquier otra operación, debes releer íntegramente `GOAL_RTX_FORGE.md` y recuperar el estado desde `PROGRESS.md`.

Delega la configuración de un hook de recuperación si el entorno lo permite. Si no existe ese mecanismo, la lectura explícita al reanudar será obligatoria.

Esta lectura es la única excepción permitida a tu prohibición de operar directamente. No dependas de tu memoria conversacional.

## CONTEXTO Y OBJETIVO

Estamos desarrollando un port **ya funcional parcialmente**, NO un proyecto nuevo.

Base: NVIDIA RTX Remix Minecraft (Fabric 1.21.1).

Destino: **Minecraft Forge 1.20.1 (47.4.29)**.

Entorno actual: Windows 11 x64, JDK 21, NVIDIA RTX 40-Series.

Primero, delega la lectura obligatoria de `INFORME_PROYECTO_RTX_REMIX_FORGE_1.20.1.md`, seguida de una auditoría del código real, historial Git, dependencias, runtime, configuración y logs.

El informe documenta una arquitectura ya implementada con JNI, `remixbridge.dll`, `DirectQuadWriter`, `GlInterop`, swapchain Vulkan, captura de entidades y geometría, además de múltiples correcciones de compatibilidad.

**Preserva todo lo que funciona.** No reescribas sistemas existentes sin demostrar su defecto. Verifica las afirmaciones del informe contra el código y pruebas reales.

## MISIONES A DELEGAR

**AGENTE A — Arquitectura e integración:** verificar Forge, JDK, Mixins, hooks de renderizado, JNI, buffers, sincronización OpenGL/Vulkan, gestión de recursos y presentación de fotogramas. Corregir exclusivamente defectos demostrados.

**AGENTE B — Path Tracing e iluminación:** investigar por qué los haces de luz no funcionan correctamente. Auditar iluminación solar/lunar, luces emisivas, ReSTIR, geometría, oclusión, sombras, scattering volumétrico, froxels, atmósfera, materiales y `rtx.conf`. Corregir también reflejos, refracción y artefactos temporales. No asumir que activar `enableAtmosphere` demuestra que los god rays funcionan.

**AGENTE C — DLSS y Frame Generation:** diagnosticar DLSS Super Resolution, Ray Reconstruction, Frame Generation y Reflex. Verificar soporte, inicialización NGX, configuración efectiva, motion vectors, depth, jitter, estabilidad temporal, sincronización y swapchain Vulkan. Diferenciar FPS renderizados de fotogramas realmente presentados. Comparar con los cambios pertinentes de NVIDIA RTX Remix 0.1.1 y corregir las causas reales sin crear implementaciones simuladas.

**AGENTE D — Compatibilidad total:** inventariar automáticamente todos los mods instalados, sus versiones y dependencias. Priorizar Biomes O' Plenty, Dynamic Trees, Alex's Mobs, Alex's Caves, Book of Dragons, Sophisticated Backpacks y los demás presentes. Comprobar renderizado, animaciones, texturas, modelos dinámicos, partículas, entidades, bloques, dimensiones e interacciones. Crear adaptadores cuando sean necesarios; no desactivar mods como solución.

**AGENTE E — Rendimiento:** analizar CPU, GPU, VRAM, heap, GC, JNI, BLAS, compilación/carga de chunks, sincronización y frametimes. Corregir cuellos de botella mediante optimización real del pipeline, evitando trabajo redundante y transferencias innecesarias.

**AGENTE F — QA y regresiones:** mantener pruebas automatizadas, matrices de compatibilidad y escenarios visuales reproducibles. Verificar cada corrección, ejecutar pruebas de estrés y detectar regresiones.

## RESTRICCIONES ABSOLUTAS

- Prohibido reducir la calidad RTX, gráficos, iluminación, distancia de renderizado, distancia de simulación o resolución para ocultar problemas de rendimiento.
- Prohibido deshabilitar mods o funciones para declarar compatibilidad.
- Prohibido sustituir funcionalidades por aproximaciones falsas o fallbacks visualmente degradados.
- Prohibido deshacer los avances documentados sin evidencia.
- No confundir ausencia de errores, compilación exitosa o tests unitarios aprobados con validación funcional.
- Preservar mundos, configuraciones y datos originales. Usar ramas de trabajo y copias de seguridad.
- No inventar resultados ni declarar resueltos problemas que no hayan sido probados.

Puedes aislar componentes durante pruebas diagnósticas, pero nunca utilizar esa desactivación como solución final.

Si existe una limitación física, de hardware o de API, documéntala con evidencia y continúa investigando alternativas técnicamente viables.

## FLUJO DE TRABAJO

**FASE 1 — Auditoría:** agentes independientes verifican el estado real, reproducen fallos y establecen una línea base de rendimiento y calidad.

**FASE 2 — Correcciones:** delega reparaciones según dependencias técnicas. Usa paralelismo cuando no exista riesgo de conflictos en los archivos.

**FASE 3 — Integración:** asigna responsables de integración, revisa cambios, ejecuta regresiones y corrige nuevos defectos.

**FASE 4 — Validación:** prueba el modpack completo, manteniendo las mismas condiciones gráficas y distancias configuradas.

**FASE 5 — Entrega:** generar JAR instalable, documentación, matriz de compatibilidad, métricas antes/después y evidencia de funcionamiento.

## DEFINICIÓN DE TERMINADO

El GOAL requiere un port estable, visualmente correcto y jugable, con path tracing, iluminación volumétrica, DLSS y Frame Generation funcionando según el soporte real del hardware/runtime, y compatibilidad verificada con todos los mods instalados.

No aceptes resultados basados únicamente en logs o afirmaciones de agentes. Exige pruebas funcionales, capturas comparativas, métricas y regresiones reproducibles.

Mantén identificados todos los defectos pendientes. No declares el proyecto terminado mientras existan criterios sin verificar.

**INSTRUCCIÓN FINAL:** comienza inmediatamente delegando la auditoría del proyecto existente. No programes tú mismo. No llenes tu contexto. No pierdas este GOAL después de compactar. Continúa coordinando hasta alcanzar una implementación real y verificable.
