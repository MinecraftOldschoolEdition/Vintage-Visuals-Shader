# PBR material performance — v1.2, 2026-09-30

RT and Vintage Visuals use the same client PBR material-volume builder. This
release reduces its CPU work without changing material words, lighting budgets,
BSDFs, authored texture maps or shader quality. Use the **PBR performance preview
client dated 2026-09-30**, or an integrated client containing this update. The older
GI-only preview does not include it. A resource-pack ZIP cannot install Java
renderer changes; both packs compose that renderer with `#include <builtin>`.

## Changes

* Visit each 48³ volume row in chunk-aligned spans. Resolve each loaded chunk once
  per span and fill missing/out-of-world spans directly.
* Encode a block's 16 metadata variants once per volume rebuild. Read metadata
  only when those variants produce different optical words, including slabs.
* Discard the encoding cache at every rebuild so changes to block optical
  properties take effect. Preserve chunk admission, revision checks, eight-block
  camera anchoring, empty-brick flags and missing-chunk opacity.

The lighting shader remains byte-for-byte the v1.1 implementation. Experiments
with sharing point-light calculations and BSDF view terms were not retained:
small isolated gains did not translate into a consistent full-scene benefit.
This release targets material-volume rebuilds, not a reduction in GPU PBR quality.

## Measurements

Linux, Ryzen 7 9800X3D, NVIDIA RTX 5080, driver 615.71.09, Java 25. The reference is
a frozen copy of the working v1.1 implementation at the start of this update.
Both versions use the same loaded 25-chunk fixtures. Fixture construction and
complete output hashing are outside the timer; 200 updates warm the JVM with
`-Xbatch`, then 31 samples are taken and the first 12 discarded.

CPU medians in milliseconds:

| Fixture / operation | v1.1 | v1.2 | Less time |
| --- | ---: | ---: | ---: |
| Dense ground / fresh volume | 0.846884 | 0.413729 | 51% |
| Dense ground / block edit | 0.839089 | 0.441962 | 47% |
| Dense ground / eight-block camera step | 0.880628 | 0.453404 | 49% |
| Sparse ground / fresh volume | 0.366488 | 0.334848 | 9% |
| Sparse ground / block edit | 0.372950 | 0.333716 | 11% |
| Sparse ground / eight-block camera step | 0.405543 | 0.338185 | 17% |

Complete volume hashes matched in every fixture. Dense ground fills y=40–61 with
stone beneath a slab/leaf surface, floor and wall. Sparse ground retains the same
surface/floor/wall but omits that fill. Separate dense repeats showed larger
baseline outliers, so the table uses the stable repeat rather than the largest
apparent gain. Results depend on block diversity, chunk implementation and JVM.

An unchanged cached frame still bypasses rebuilding. Dense cached-frame medians
were 0.000136 → 0.000170 ms (1,000 updates per sample); another run measured
0.000158 → 0.000155 ms. The few tens of nanoseconds of variation do not establish
a cached-frame gain. These measurements are CPU material-preparation costs, not
whole-frame or FPS speedups.

A disposable native client scene selected the actual Vintage Visuals and RT
packs at a fixed camera, with metal, wood, stone, glass, foliage and glowstone,
1280×720 output and frozen daylight/weather. Five seconds of warmup preceded
each five-second GPU timestamp interval:

| GPU pass / intervals | v1.1 (ms) | v1.2 (ms) |
| --- | ---: | ---: |
| Vintage WORLD_SCENE | 1.4988 / 1.5042 | 1.5031 / 1.5082 |
| RT WORLD_SCENE | 4.8169 / 4.7969 | 4.8237 / 4.8228 |
| RT irradiance | 0.0430 / 0.0423 | 0.0423 / 0.0425 |

This shows no material GPU scene improvement, which is expected because the
released shaders are unchanged. These are pass intervals, not whole-frame/FPS
results. Both Vintage captures matched v1.1 pixel for pixel. RT comparisons
changed 10,074 and 21,227 of 921,600 pixels; repeated captures also varied within
each run (19,281 pixels in v1.1 and 6,801 in v1.2). RT screenshots therefore do not
establish exact output parity; the exact volume and fragment comparisons below
provide the stronger evidence.

## Correctness and artifact checks

* 42 focused PBR, shared-lighting and irradiance shader tests passed, without
  failures or skips. A frozen independent scalar volume reference matches all
  110,592 words and revisions across 32 camera/edit steps, negative coordinates,
  world limits, missing/arriving chunks, all slab metadata and changed opacity.
* The production-fragment direct-light differential probe covers 384 fixtures,
  16 receiver material profiles, all four qualities, grid fallback, source-mask
  boundaries and thin geometry. All 34,603,008 checked channels/masks/evaluation
  values match the v1.1 reference exactly.
* The complete production fragment probe checks 1,400 alpha/material cases,
  240 authored material-map cases and 180 material-map rebase cases. Native
  Vulkan map sampling also passes 96 finite-result cases, including invalid UVs,
  LODs, indices, mip bounds and emission filtering.
* `shadowJar` passed. The packaged client is checked separately from loose
  compiled classes. GPU probes ran on NVIDIA; this is not native evidence for
  other vendors or extended gameplay.

## Reproducing

From a format-3 client already containing v1.1, apply
`engine/pbr-performance.patch` from the `client-source` root, then copy
`engine/verification/minecraft/` into `minecraft/`. Older format-3 clients also
need the cumulative v1.1 GI patch first; see [README.md](README.md).
Run from the Gradle root:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew \
  -I /path/to/engine/verify.init.gradle giClasspath test \
  --tests '*VulkanPbr*Test' --tests '*VulkanHardwareIrradianceShaderTest' \
  --tests '*VulkanColoredLightShaderTest' shadowJar \
  --no-build-cache --no-parallel --max-workers=1
```

`giClasspath` prints the test runtime classpath. With that value as `PBR_CP`:

```sh
PBR_JAVA=/usr/lib/jvm/java-25-openjdk/bin/java
"$PBR_JAVA" -Xbatch -Xmx2g -Dpbr.probe.denseGround=true -cp "$PBR_CP" \
  net.minecraft.src.graphics.backend.vulkan.VulkanPbrVolumePerformanceProbe
"$PBR_JAVA" --enable-native-access=ALL-UNNAMED -Xmx2g -cp "$PBR_CP" \
  net.minecraft.src.graphics.backend.vulkan.VulkanPbrDirectLightGpuProbe
"$PBR_JAVA" --enable-native-access=ALL-UNNAMED -Xmx2g -cp "$PBR_CP" \
  net.minecraft.src.graphics.backend.vulkan.VulkanPbrAlphaGpuProbe
"$PBR_JAVA" --enable-native-access=ALL-UNNAMED -Xmx2g -cp "$PBR_CP" \
  net.minecraft.src.graphics.backend.vulkan.VulkanPbrMapGpuProbe
```

Omit `denseGround` for the sparse fixture. Frozen CPU class snapshots must include
matching built-in `data/` resources beside their classes. For packaged-client
validation replace the main-class and main-resource classpath entries with the
rebuilt JAR, retaining test classes/resources and dependencies. Native probes
require a compatible GPU/display; compile-only checks do not prove GPU output.
