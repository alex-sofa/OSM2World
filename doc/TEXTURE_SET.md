# Texture sets (experimental, off by default)

OSM2World can add photo textures that a texture server publishes per OSM object, in the format `osm3d-texset/0.3`
(specification: `docs/TEXTURE_FORMAT.md` and `docs/TEXTURE_CLIENT_SPEC.md` in https://github.com/alexschultze/dev-open-3d-builder).

```properties
# one or more bootstrap URLs (comma-separated), each serving textures/<region>/latest.json
texsetUrl = https://textures.malpy.de/
texsetRegion = bodensee
```

When `texsetUrl` is set, the conversion loads `latest.json`, picks a mirror (weighted random, failover to the others),
loads `manifest.json` and the cell indices (XYZ z16) covering the data bounds, and adds a textured quad for every surface
it can match. Without the property nothing changes.

| Spec item | Status |
| --- | --- |
| format check (`osm3d-texset/0.3` exactly) | yes |
| **walls** of building parts mapped as ways: key `w<way id>:wall:<node a>-<node b>` from OSM2World's node ids (either node order; the stored order defines u) | yes |
| geometry check | by the stored `edge` with the 0.3 m position tolerance: the map projection snaps positions to millimetres, so the exact `geom_hash` cannot be reproduced |
| wall frame (u = a -> b, v up from the part's `min_height`), outer face from the ring winding, rectangle clipped to the wall height | yes |
| **node faces** `n<id>:face` (billboards): quad on the authoritative `plane`, `height_m` above the node's ground | yes |
| billboards without `width`/`height` tags: the record's size is an assumption just like OSM2World's default model, so the texture is put onto OSM2World's own poster (texture rectangle stretched to it) instead of a second quad on the `plane` | yes |
| pages: `web` derivative (WebP), checked against `sha256`; decoded with the TwelveMonkeys ImageIO WebP reader (`core-jvm`) | yes |
| T0: if a page cannot be loaded, the quad gets the surface's T0 colour | yes |
| building parts mapped as multipolygon relations (the key needs the member way id), roofs, ground, relief | no |
| attribution line | not yet: the attribution entries of the records are not passed to the outputs |

Implementation: `org.osm2world.texset` (`TexsetClient`, `TexsetSurface`, `TexsetPageTexture`), called from
`O2WConverterImpl` after elevation calculation. The quads are extra representations of the building part or node,
3 cm in front of the surface, so all outputs (OpenGL, glTF, OBJ, ...) include them.
