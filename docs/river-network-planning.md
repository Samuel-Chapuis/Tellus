# River network planning

River elevations now come from `RiverNetworkPlan`, rather than the former
expanded-water median and 3x3-chunk height cap. Both generator families consume
the shared resolver; neither removes water blocks after generation to enforce
a chunk mean.

## Design

- Overture line and riverbank footprints (including the configured width
  expansion) remain geographic inputs. Islands and dry gaps remain dry.
- World-anchored 256-block planning tiles read a 16-block halo. Connected
  water forms a routing graph. Shared, two-sided border sections have fixed
  elevations; adjacent callers do not derive them from their own chunk means.
- Routes prefer the interior of the channel and join the lowest available
  border/lake/ocean anchor. Non-increasing, isotonic profiles remove local DEM
  bumps. Elevations are quantized into two-block level reaches; this is not a
  fractional-height custom fluid renderer.
- Axis levels are extruded across the mapped channel, then connector constraints
  are applied. A bank-distance profile gives the bed a depth of 1–6 blocks.
  Expansion terrain is excluded from original-channel height estimates when
  original samples are available.
- A lower column adjoining a higher reach is carved as a receiving column for
  vanilla falling water. Existing source tick scheduling starts the flow;
  infinite-source conversion is suppressed inside planned river channels,
  only between their bed and water top (not in builds above or below them).
- Full terrain, preview and DH use the same block-space plans. Coarse cells
  sample the existing DH footprint if their centre misses a narrow channel.
  LODs represent falls as visible water columns without fluid simulation.
- The bounded in-memory cache holds at most 128 plans, uses a plan version and
  the resolver generation, and is cleared with water/terrain source caches.

The implementation is original Java inspired by Streams' separation of channel
sections, level reaches and falls. It does not copy its procedural river paths,
Scala runtime, custom blocks or currents. See `THIRD_PARTY_NOTICES.md`.

## Scope and validation

`RiverNetworkPlanTest` covers noisy profiles, width expansion, bed depth,
confluences and interior tributaries, lake anchors, cliffs, islands, negative
coordinates, rebuild determinism, four-tile corners and transverse sections.
Preview tests verify that the plan overrides both line/polygon DEM rules at
multiple sampling resolutions. Existing water/LOD tests remain applicable to
non-planned water and the legacy ESA fallback.

Planning is bounded, not a global watershed simulation. Flow direction is
inferred from elevation, since the mapped footprint does not supply a directed
hydrological graph. Shared boundaries fix local continuity, but noisy source
data over several planning tiles can still give inconsistent basin-scale
directions. Lake anchors use mapped feature elevation hints; errors or clipped
features in those inputs require separate data validation. Very coarse views
retain the sampling limitations of their water raster.

## In-game acceptance check

1. Use a **new test world or new chunks** at the location exhibiting the issue.
   Previously generated chunks and saved DH terrain are not rewritten.
2. Compare width scales 1 and an expanded width on the same location. Look
   along and across the surface, including bends and confluences.
3. Cross chunk and planning-tile borders in both generation orders. Confirm no
   square height caps, missing bed columns or floating edge ribbons.
4. At a slope/drop, let fluid ticks settle and reload the world. Check that the
   falling sheet remains supplied and does not fill into an infinite-source wall.
5. Compare preview, near terrain and rebuilt DH data. Check lake/outlet joins,
   and profile first-load time and memory at a wide river.

Automated checks do not replace this visual/fluid-tick validation with real
geographic data, shaders and DH enabled.
