# Parallax occlusion mapping (optional)

Status: prototype · off by default · JOGL shader renderer only

## What it does

Parallax occlusion mapping (POM) uses the **displacement texture** of a material's first texture layer
(`TextureLayer.displacementTexture`) to fake relief such as brick joints, roof tiles or cobblestones on flat polygons.
The fragment shader marches the view ray through a height field in equally spaced layers and refines the hit point
by linear interpolation between the last two samples. No geometry is added.

## Enabling it

POM is only used with the shader based JOGL implementation (`joglImplementation=shader`). Config properties:

| Property              | Default | Meaning |
|-----------------------|---------|---------|
| `useParallaxMapping`  | `false` | master switch; with `false` rendering is identical to before |
| `parallaxHeightScale` | `0.05`  | depth of the relief in metres (displacement value 0 lies this far below the polygon) |
| `parallaxLayers`      | `16`    | maximum number of ray marching steps (2 to 64); fewer are used when looking straight at the surface |
| `parallaxFadeStart`   | `20`    | camera distance in metres where the effect starts to fade (the relief depth shrinks towards plain texturing) |
| `parallaxFadeEnd`     | `40`    | camera distance in metres from which POM is skipped completely |

Only materials whose first texture layer has a displacement texture are affected. Convention: displacement value 1 (white)
is the polygon surface, 0 (black) is `parallaxHeightScale` metres below it.

## Where it hooks in

- `opengl/src/main/resources/shaders/default.fragment`: function `parallaxTexCoord`, used for the first texture layer.
- `DefaultShader`: binds the displacement texture to a dedicated texture unit and sets the uniforms.
- `ParallaxMappingParameters` (read from `O2WConfig`) and `JOGLRenderingParameters`: carry the settings.

The shader needs **no tangent attribute**. The mapping between surface and texture space is derived per fragment from screen space
derivatives of the eye space position and the texture coordinates (`dFdx`/`dFdy`). This also works with non-uniform texture scaling,
because texture coordinates in OSM2World are scaled to the real world size of the texture.

## Limitations

- Only the first texture layer is displaced. The coordinates of the other layers are not shifted.
- Lighting still uses the geometric normal: normal maps are not applied by the JOGL shader yet (`useBumpMaps` is disabled in
  `DefaultShader`). The `normalTexture` and `ormTexture` of a layer are not loaded by the JOGL renderer.
- No self shadowing, no silhouette clipping at the polygon edges, and the depth buffer is not adjusted.
- Not verified on a real OpenGL context during development, see the commit message for what was tested.

## Other consumers

- **glTF / glb output** does not write displacement textures. glTF has no standard displacement extension.
- **Web module** (`core-web`): `WebModule.O2WMesh` already exports `displacementTexture` per mesh. A JavaScript or three.js consumer can
  implement the same algorithm in its material (for example with `onBeforeCompile`) and use the exported file name.
  The parameters above (layers, height scale in metres, distance fade) carry over unchanged.
- **Frontend PBF output** already stores the displacement texture URI per texture layer.
