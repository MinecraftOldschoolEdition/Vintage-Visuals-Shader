# Changelog

## v1.2 — 2026-09-30

* Reduce PBR material-volume rebuild work with chunk-aligned spans and encodings
  cached within each rebuild. Preserve metadata-sensitive materials and opacity
  changes, complete volume words, revision behavior and unloaded-cell handling.
* Dense-ground fresh/edit/camera fixtures measured 47–51% less CPU preparation
  time; sparse fixtures measured 9–17% less time. Cached-frame and GPU lighting
  behavior are unchanged. These are not whole-frame speedup claims.
* Label the in-game pack description v1.2. Preserve pack-local settings/sliders,
  authored material maps, shader quality and cumulative v1.1 GI improvements.
* Include the PBR engine patch, independent reference, verification sources and
  measured report. Release ZIPs retain assets and metadata at their root.

**Requires the new PBR performance preview client or an integrated updated client.**
See [PBR_PERFORMANCE.md](PBR_PERFORMANCE.md) and [engine/README.md](engine/README.md).

## 1.1 — 2026-09-30

* Shared GI engine update: reuse cached optical column runs, preserve integer
  transport with a decay table, and reduce unchanged-frame allocations.
* RT irradiance traces rays in parallel while accumulating SH/AO in the original
  order. Budgets, ray counts and material evaluation remain unchanged.
* Include the engine patch, verification sources and measured performance report
  in this repository and the companion engine source ZIP.
* Add reproducible release packaging with `assets/` and `pack.mcmeta` at ZIP root.

**Requires the updated client renderer for the performance gains.** The playable
pack ZIP retains the existing rasterized shader interface, settings and defaults.
See [PERFORMANCE.md](PERFORMANCE.md) and [engine/README.md](engine/README.md).

## 1.0

Initial standalone Vintage Visuals shader pack with pack-local controls and quality sliders.
