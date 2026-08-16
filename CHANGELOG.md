# Changelog

Fork versions of Colorful Lighting: Sodium/Iris Edition. Upstream (erykczy) releases are 1.x; this fork
starts at 2.0.0.

## 2.3.1

### Shader packs: second hook family (vertex-lit)

Colored light is injected into the shader pack's own lighting, so it depends on hooks the pack exposes.
Until now only one family was recognized. MakeUp reported correct brightness but no color
(`37 sanitized, 0 tinted`), because it lights in a completely different place.

| Family | Where block light is built | Detection marker |
|---|---|---|
| Fragment-lit (Complementary) | fragment, `vec3 blocklightCol` consumed by `DoLighting(color,` | both strings present in the fragment |
| **Vertex-lit (MakeUp, E-LITE)** | **vertex, `candleColor` (from `lmcoord.x`), shared by terrain and entity programs** | `candleColor = clamp(candleColor,` present in the vertex |

Detection is by content, never by pack name, so derivatives of either family work unchanged.

`IrisShaderCompat` additions:

- `candleBlockLightEnd(source)` — offset of the end of the first `candleColor = …` assignment that is not
  one of the pack's later combining steps (`max(`, `clamp(`).
- `tintCandleColor(source, colorExpression)` — inserts the hue swap there (magnitude preserved, same
  formula as the fragment-side `blocklightCol` swap).
- `tintHandLight(source)` — gives the pack's own held-item light the held emitter's color, using the
  existing `…_HeldColor` / `…_HeldColor2` uniforms; no-ops when the pack has no held-light branch.
- `supports(vertex, fragment)` now accepts either family; `supportsVertexTint(vertex)` is the new probe.

Call sites: `patchVertex` (Sodium/Iris terrain path) and `sanitizeVanillaVertex` (vanilla-format programs)
both call `tintCandleColor` — one hook covers both pipelines because MakeUp shares the vertex include.
`TransformPatcherMixin` now tracks `vertexTint` and `fragmentTint` separately; vertex tinting is allowed
across geometry/tessellation stages because it needs no varying.

### Held light must stay outside the colorful normalization

First attempt injected the tint after the pack's closing `candleColor = clamp(…)`. That value already had
the held-item light maxed into it, so holding a light near a colored source repainted the held light with
the block's hue, normalized to full saturation — large saturated patches across the terrain. Same failure
class as the Complementary held-light ring fixed in 1.3.2, and fixed the same way: keep held light out of
the colorful path. The tint now lands immediately after the block-light assignment, before the pack's
`max(candleColor, handLight)`. Verified in the Iris debug dump (`018_terrain_solid.vsh`):

```
341  candleColor = vec3(…) * (illumination.x * …);   pack: block light
345  colorfulCandleTint …                            ours: block-light hue swap
353  vec3 handLight = vec3(…) * (…);                 pack: held light
357  colorfulHandTint … handLight = …                ours: held-light hue (own magnitude)
362  candleColor = max(candleColor, handLight);      pack: combine
364  candleColor = clamp(candleColor, …);            pack: clamp
```

### Renderer version ranges relaxed

`sodium` and `iris` optional dependencies changed from pinned upper bounds to `[tested version,)`. Newer
renderer builds usually keep the hooked internals; when they do not, the patcher skips the affected hooks
and reports counts in the log instead of the loader blocking startup. README now lists tested versions
rather than hard requirements. Minecraft/NeoForge stay pinned to 1.21.1 (mixin targets are version-exact).

### Deliberately unchanged

The fragment-lit path (blocklightCol swap, held-light helper, per-pixel entity lights), the packed-light
sanitizing, the block engine, and the entity light manager are untouched. The vertex-lit family is purely
additive: with no `candleColor` in the source, every new function returns its input unmodified.

### Verification

| Case | Result |
|---|---|
| MakeUp UltraFast 9.5d | 0 → **27** programs tinted, terrain path enabled, 0 shader compile errors |
| Injection ordering | confirmed in the Iris debug dump (table above) |
| Complementary Unbound r5.8.1 | 27 tinted + 29 per-pixel entity lights, 0 shader errors — unchanged from 2.3.0 |
| E-LITE 5.1.1 | uses `candleColor`, so covered by the same family (not run in-game yet) |

Known limitation: vertex-lit packs have no `lmCoordM` / `GetHeldLighting` equivalents, so per-pixel entity
lights fall back to the block-light engine (quantized to blocks) under MakeUp and E-LITE.

### Testing-environment note (for whoever reproduces this)

A Minecraft client killed from outside still writes `config/iris.properties` and `options.txt` on the way
out, so editing those files while an old client is shutting down silently loses the edit — and a run can
end up using a different shader pack than intended. During this work that produced a summary pair that
looked like a reload bug:

```
27 with colored-light tint, 29 with per-pixel entity lights   <- creation 1 (Complementary)
27 with colored-light tint,  0 with per-pixel entity lights   <- creation 2 (MakeUp, unintended)
```

Both lines were correct for their pack. Always confirm the pack from the `Using shaderpack:` log line
before drawing conclusions from these counters.

## 2.3.0

Smooth shader-pack entity lights (lightmap-equivalent, LambDynamicLights brightness semantics) plus a
client config screen for the built-in entity light levels (0 disables each).

## 2.2.0

Entity light sources: `entity_emitters.json` for static per-type colors, `EntityLightSources.register` for
state-driven colors, burning entities emit fire-colored light.

## 2.1.0

Blockstate-specific emitter/filter keys (`modid:block[prop=value]`).

## 2.0.1

Sodium and Iris made optional (mixin plugin gates the compatibility layer).

## 2.0.0

Merged `colorful_lighting_sodium_compat` into the mod and fixed upstream defects at the source (emissive
fall-through, entity light overrides, trilinear sampling, ghost light, light-engine-replacement support,
storage tearing, worker resilience, config robustness).
