# Shared GI engine update 1.1

This directory contains the actual renderer optimization used by RT and Vintage
Visuals. Both packs compose the client engine with `#include <builtin>`. A resource
pack cannot replace these Java classes or the engine compute dispatch by itself.

Use a client build incorporating this update (GI performance preview 2026-09-30 or
a later integrated build). For source builds, apply the patch from `client-source`:

```sh
git apply --check /path/to/engine/gi-performance.patch
git apply /path/to/engine/gi-performance.patch
```

The patch changes only the four GI production files; it is based on the format-3
client source as it stood on September 30, 2026. `source-manifest.json` records the
exact before/after file hashes. Older clients may require integrating the changes
into their corresponding files. Do not apply the patch again to an updated client.

`verification/` contains snapshots of the affected tests/probes, including the
frozen serial compute reference. Copy `verification/minecraft/` into the source
checkout's `minecraft/` to run them. The existing client test fixtures and renderer
are their dependencies. `verify.init.gradle` narrows compilation to Vulkan tests
and the world fixture; see `PERFORMANCE.md` for commands and measured limits.

The source bundle is separate from the playable pack ZIP. The playable ZIP has
`assets/` and `pack.mcmeta` at its root and does not need extracting into another
nested directory. The companion source ZIP is not itself a resource pack.
