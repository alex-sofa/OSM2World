# Roadmap: streamed textures per OSM entity

Status: draft · scope: phase 1 covers **walls only** · **implemented (experimental): walls and node faces with the `web` derivative, see [TEXTURE_SET.md](TEXTURE_SET.md)**

## Goal

Textures are served as static files over an API and belong to OSM entities, not to meshes. A client builds the geometry from OSM data
(e.g. with OSM2World) and finds the texture of every wall by a stable key. Texture sets are produced by a separate pipeline; this
repository only needs to consume them.

## Principles

- **Keys are derived from OSM data only.** No OSM2World internals enter the key, so the texture database stays independent of this project.
- **One implementation of key generation** (a small `surface-keys` module) shared by the client and the pipeline, verified with shared test vectors.
- **No OSM tag to mark textured objects.** The per-cell index already lists which objects and walls have textures (one request per cell,
  not per object). Objects missing from the index keep their procedural material.
- Unmapped or stale surfaces fall back to the procedural OSM2World material (T0: one colour and material class).

## Wall key

```
<w|r><osm id>:wall:<node a>-<node b>
```

- `<node a>-<node b>` are the OSM node ids of the wall edge, in way order; for multipolygons the same rule applies to inner rings.
- Example: `w977528760:wall:9032145-9032146`.
- The index stores the OSM version and a geometry hash per surface; on a mismatch the client falls back to T0.
- Roofs, ground and other surfaces are out of scope for phase 1.

## Phases

### Phase 0 – Specification
- Fix the wall key rules, including ways with repeated nodes, closed ways, multipolygon rings and `building:part`.
- Collect test vectors (OSM extract → expected keys).

### Phase 1 – Wall keys (no change to OSM2World required)
- `surface-keys` module with test vectors.
- Cell index (`cells/<z>/<x>/<y>.json`, z16) with, per wall: `t0` colour and material, page, `uv`, `origin`, `size_m`, `fit`, `source`.
- Client: look up each wall by key, otherwise use the procedural material.

### Phase 2 – Optional OSM2World integration
- Investigate how OSM2World splits walls (attachments, doors, windows in `ExteriorBuildingWall` / `WallSurface`) compared with one OSM edge per key.
- If OSM2World should apply the textures itself: a `TextureData` subclass fetching the texture, a style hook resolving a material per wall key,
  and a `TexCoordFunction` computing coordinates from the index (`uv`, `origin`, `size_m`).
- Keep texture-carrying meshes grouped by atlas page so `MeshStore.MergeMeshes` does not create excessive draw calls.

### Phase 3 – Further surfaces
- Roofs (keyed by boundary nodes rather than a face number), other object surfaces, ground as an XYZ tile pyramid.

## Open questions

- Does one wall key always correspond to exactly one wall surface (splits at attachments, building parts, shared walls)?
- Quality levels and range-request mip streaming (KTX2) are a client concern; Java would need a different image format (e.g. WebP/PNG derivatives).
- Licensing: OSM2World is LGPL; texture sources are gated by their licence before publishing.
