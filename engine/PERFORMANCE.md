# GI performance update — 2026-09-30

The RT and Vintage Visuals resource packs compose the shared client renderer with
`#include <builtin>`. These optimizations are in that renderer. Installing a new
resource-pack ZIP alone does not update the Java renderer or its compute shader;
use the rebuilt client or integrate the companion `engine/gi-performance.patch`.
Pack IDs, settings, effect defaults and Low/Medium/High/Ultra budgets stay unchanged.

## Changes

* Expand the already cached emission/opacity column runs into overlapping colored
  GI tiles instead of reading every block again for each tile. Missing chunks and
  below-world cells remain opaque; loaded above-world cells remain air.
* Use a small exact integer decay table, reuse the first decay for open neighbors,
  and replace queue modulo operations with wrap branches. Each original RGB8
  rounding step and the maximum-color propagation rule are preserved.
* Skip temporary column-retention/change sets and palette resets on stationary
  frames with unchanged optical inputs. Chunk revisions still get checked.
* Trace the RT probe's rays in parallel: 32 lanes per probe, two probes in each
  64-lane workgroup. Accumulate contributions in the original sample order after
  a uniform workgroup barrier. The dispatcher uses the matching workgroup count.

This changes scheduling and redundant work, not lighting quality. Source discovery,
tile residency, invalidation, probe refresh budgets, ray counts, SH/AO layout,
material evaluation and fenced cache migration keep their existing behavior.
The CPU tile changes benefit both packs because RT also uses the shared colored
lighting reference and rasterized fallback.

## Measurements

Linux, Ryzen 7 9800X3D, NVIDIA RTX 5080, driver 615.71.09, Java 25. The reference is
a frozen copy of the working renderer immediately before this update, rather
than an older Git revision containing unrelated differences. Timings exclude
startup, fixture construction and shader compilation.

CPU medians (milliseconds):

| Work | Before | After | Less time |
| --- | ---: | ---: | ---: |
| Fresh colored-GI tiles, 25 loaded columns | 64.623 | 34.271 | 47% |
| Optical block edit and neighbor refresh | 21.288 | 11.483 | 46% |
| Unchanged cached frame | 0.04444 | 0.01221 | 73% |
| 48³ sparse-air transport | 1.431 | 0.848 | 41% |
| 48³ dense-emitter transport | 5.173 | 3.517 | 32% |
| 48³ mixed-opacity transport | 8.265 | 6.706 | 19% |
| 48³ absorbing transport | 1.059 | 0.414 | 61% |

Transport uses 31 runs, dropping 12 warmups. Tile builds use 15 runs, dropping six;
edits use 21, dropping eight. Cached-frame samples each contain 1,000 updates,
reported per frame. Complete output hashes matched the reference in every fixture.
Independent repeats put fresh builds at 34.08–34.88 ms and edits at 11.47–11.92 ms.

Isolated RT compute medians measured with native Vulkan timestamps, a 50,000-AABB
scene and 2,048 stored probes (25 submissions, dropping six warmups):

| Quality | Updated probes | Rays per probe | Before (ms) | After (ms) | Less time |
| --- | ---: | ---: | ---: | ---: | ---: |
| Low | 256 | 8 | 0.0706 | 0.0236 | 67% |
| Medium | 384 | 12 | 0.0970 | 0.0300 | 69% |
| High | 512 | 24 | 0.1665 | 0.0302 | 82% |
| Ultra | 1,024 | 32 | 0.2157 | 0.0661 | 69% |

Ultra measured 0.0912 ms in another warmed run: the measured reduction there was
58%. GPU clock state and scene occupancy affect this small compute pass. These
are pass timings, not whole-frame speedups or FPS claims.

A disposable native client scene selected the actual Vintage Visuals and RT
packs, with foliage, glass, metal, wood, stone and glowstone, a fixed camera,
1280×720 output and frozen daylight/weather. Two RT intervals measured irradiance
at 0.1483/0.1486 ms before and 0.0425/0.0418 ms after (71–72% less time). RT world
raster/shading remained approximately 4.82 ms and AS updates approximately 0.10 ms;
these stages were not optimized here. Each interval follows five seconds of
warmup and collects five seconds of production GPU timestamps.

## Correctness and artifact checks

* 56 focused transport, tiles, shader and irradiance tests passed, with no skips.
  Differential transport covers random 1³/2³/5³/16³/48³ fields, all channel values
  and absorption levels, queue wrapping and converged/reused fields. Tile checks
  include seams, negative coordinates, bottom/top height, missing chunk halos,
  arrival/removal, camera moves and optical/non-optical edits.
* Native serial-versus-parallel compute matched all 284,256 tested SH/AO/position
  words bit for bit. Fixtures cover open space, opaque walls, foliage, cutout,
  fluid and emissive materials at all four qualities; invalid probes, cursor
  wrapping, odd update counts and partial workgroups are included.
* Native irradiance sampling passed 147 assertions. Migration preserved 2,304
  completed probes exactly and reproduced 57,600 SH/AO words after bounded refresh.
* Native raster GI passed steady/off/on/source removal/source restoration checks,
  eight frames per state: 34,287 receiver pixels and 12,450 lava-colored pixels.
  Reference and rebuilt-client captures matched pixel for pixel.
* Controlled full-scene Vintage captures matched pixel for pixel. Corresponding
  RT captures differed in 38–40 of 921,600 pixels; RT's own repeated captures also
  varied. Exact compute comparison above is the stronger RT transport evidence.
* `shadowJar` passed. The rebuilt JAR also passed the native 284,256-word RT
  differential probe and native raster GI probe. Validation ran on NVIDIA Vulkan;
  this is not native evidence for other GPU vendors or extended gameplay.

## Reproducing

The companion engine bundle contains the patch, a snapshot of the affected
verification sources, and `verify.init.gradle`. Apply the patch from the
`client-source` root, copy `engine/verification/minecraft/` into `minecraft/`,
then run from the Gradle root:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew \
  -I /path/to/engine/verify.init.gradle giClasspath test shadowJar \
  --tests '*VulkanGiTransportTest' --tests '*VulkanColoredLightTilesTest' \
  --tests '*VulkanColoredLightVolumeTest' --tests '*VulkanColoredLightShaderTest' \
  --tests '*VulkanHardwareIrradiance*Test' \
  --no-build-cache --no-parallel --max-workers=1
```

`giClasspath` prints the test runtime classpath. Use it to run
`net.minecraft.src.graphics.backend.vulkan.VulkanGiTransportPerformanceProbe`,
`VulkanHardwareIrradiancePerformanceProbe`,
`VulkanHardwareIrradianceParallelGpuProbe`, `VulkanHardwareIrradianceGpuProbe`,
`VulkanHardwareIrradianceMigrationGpuProbe` and `VulkanColoredLightNativeProbe`.
Native probes need `--enable-native-access=ALL-UNNAMED` and a compatible Vulkan
device/display. The performance probe accepts `-Dgi.probe.computeSource=FILE`
and `-Dgi.probe.probesPerGroup=64` for the frozen serial reference. Production
uses two probes per workgroup. Frozen class snapshots used for CPU comparisons
must contain the matching built-in `data/` resources beside their classes.
