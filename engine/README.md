# Shared GI and PBR engine update v1.2

RT and Vintage Visuals compose the client renderer with `#include <builtin>`.
Resource packs cannot replace its Java classes or engine compute dispatch. Use
this release's **PBR performance preview client dated 2026-09-30**, or an integrated
build including both updates. The v1.1 GI-only preview lacks the PBR optimization.

For a format-3 source client already incorporating v1.1, run from `client-source`:

```sh
git apply --check /path/to/engine/pbr-performance.patch
git apply /path/to/engine/pbr-performance.patch
```

For the pre-v1.1 format-3 source base, apply `gi-performance.patch` first using the
same check/apply commands, then apply `pbr-performance.patch`. The GI patch changes
four production files; the PBR patch changes only `VulkanPbrMaterialVolume.java`.
The pure BSDF and world lighting shader remain the v1.1 implementation. Never
apply an update twice. Older or independently modified clients may need manual
integration. `source-manifest.json` records each patch's before/after hashes and
the rebuilt client SHA-256.

`verification/` contains the GI and PBR test/probe snapshots and frozen reference
sources. Copy `verification/minecraft/` into the source checkout's `minecraft/`;
the existing client fixtures and renderer provide their remaining dependencies.
`verify.init.gradle` narrows test compilation to Vulkan and the world fixture.
See [PBR_PERFORMANCE.md](PBR_PERFORMANCE.md) for this release's measurements and
commands, and [PERFORMANCE.md](PERFORMANCE.md) for the retained v1.1 GI results.

The playable pack ZIP has `assets/` and `pack.mcmeta` at its root. The companion
`GI-engine-update-1.2.zip` contains this cumulative source directory and is not a
resource pack. The preview client is a separate release asset; importing the pack
ZIP does not replace an older installed client.
