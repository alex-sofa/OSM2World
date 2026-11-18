# Texture sets (experimental, off by default)

OSM2World can add photo textures that a texture server publishes per OSM object, in the format `osm3d-texset/0.4`
(specification: `docs/TEXTURE_FORMAT.md` and `docs/TEXTURE_CLIENT_SPEC.md` in https://github.com/alexschultze/dev-open-3d-builder).

```properties
# one or more cdn URLs (comma-separated), each serving textures/<region>/latest.json
texsetUrl = https://textures-cdn1.malpy.de/
texsetRegion = bodensee
```

When `texsetUrl` is set, the conversion loads `latest.json` from every configured cdn (the highest `release` wins), picks a cdn from its `cdns`
(weighted random, failover to the others),
loads `manifest.json` and the cell indices (XYZ z16) covering the data bounds, and adds a textured quad for every surface
it can match. Without the property nothing changes.

| Spec item | Status |
| --- | --- |
| format check (`osm3d-texset/0.4` exactly; older versions are not read) | yes |
| **walls** of building parts mapped as ways: key `w<way id>:wall:<node a>-<node b>` from OSM2World's node ids (either node order; the stored order defines u) | yes |
| geometry check | by the stored `edge` with the 0.3 m position tolerance: the map projection snaps positions to millimetres, so the exact `geom_hash` cannot be reproduced |
| outward test (`outward`): the outer normal of the matched edge must be within 45° of the record | yes |
| wall frame (u = a -> b, v up from the part's `min_height`), outer face from the ring winding, rectangle clipped to the wall height | yes |
| height model (`wall_m`): v in metres from OSM2World's wall base, clipped at its wall top; stretched to OSM2World's wall height only when `basis.height` is `assumed` | yes |
| party walls (`shared`): the record holds only the exposed rectangle; matched by key like other walls | yes |
| **wall chains** `w<way id>:walls:<first>-<last>`: `nodes` found in the ring (either direction), every node checked against `chain` (0.3 m), outward test on the chord, u = arc length scaled to the stored length if they differ by less than 2 % | yes |
| part walls with `outline_key`: OSM2World draws the parts of Simple 3D Buildings, so they match by their own key; the outline fallback is not needed | n/a |
| **node faces** `n<id>:face` (billboards): quad on the authoritative `plane`, `height_m` above the node's ground | yes |
| `basis.size` = `assumed` on a billboard: the texture is put onto OSM2World's own poster (texture rectangle stretched to it) instead of a second quad on the `plane`; `tagged` and `measured` sizes use the `plane` | yes |
| pages: `web` derivative (WebP), checked against `sha256` and decoded once before use (TwelveMonkeys ImageIO WebP reader 3.15.3 in `core-jvm`; 3.12.0 failed on some lossless pages) | yes |
| T0: if a page cannot be loaded or decoded, the quad gets the surface's T0 colour | yes |
| building parts mapped as multipolygon relations (the key needs the member way id), roofs, ground, relief, `regions` | no |
| attribution line | not yet: the attribution entries of the records are not passed to the outputs |

Implementation: `org.osm2world.texset` (`TexsetClient`, `TexsetSurface`, `TexsetPageTexture`), called from
`O2WConverterImpl` after elevation calculation. The quads are extra representations of the building part or node,
3 cm in front of the surface, so all outputs (OpenGL, glTF, OBJ, ...) include them. From about 100 m the 3 cm offset
is below the depth precision of the OpenGL view and the wall shows through the quad (a polygon offset would fix it).
