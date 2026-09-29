# Vintage Visuals

A standalone shader resource pack for Minecraft Oldschool Edition. Enable this pack
in Resource Packs, then select **Vintage Visuals** in Video Settings → Shaders (or use resource
pack priority). Its shader entry points compose the renderer's shared GLSL library,
so engine descriptor layouts and material support stay compatible without duplicating
all native renderer code in every pack.

This pack selects **Rasterized** lighting. The **Enable this shader pack** row turns it off/on;
select the other pack to change lighting implementations. Effects and quality levels
are saved separately for each pack. Existing global shader settings migrate once
into the corresponding Vintage Visuals or RT profile.

Requires Vulkan and the client supporting shader manifest **format 3**. RT retains
the existing rasterized fallback on devices where hardware ray tracing is unavailable;
the menu reports that fallback. Only one shader pack is active at a time; textures
from the other enabled resource packs still stack normally.

Effect quality controls use stepped Low/Medium/High/Ultra sliders. Values remain
local to this pack. Drag or click to choose; release to apply. Arrow keys adjust
one step, and Home/End select the endpoints after focusing the slider.
