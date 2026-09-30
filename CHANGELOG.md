# Changelog

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
