# Adding colored-light support for a shader pack

Colored **tint** support is per-pack-family by nature: Iris has no standard "block
light color" interface, so each family gets its hue injected by targeted source
surgery. Everything pack-agnostic is already handled for you — an unsupported pack
still renders **correct vanilla-brightness light** (the universal `iris_UV2` sanitize
plus a per-pipeline CPU fallback guarantee that); a family adds the color on top.

## Architecture

```
dev.colorfullighting.compat.iris
├── IrisShaderCompat          ← pack-agnostic core: attribute/iris_UV2 decode,
│                                varying wiring, family routing. Rarely touched.
└── family/
    ├── ShaderFamily          ← the interface you implement
    ├── ShaderFamilies        ← ordered registry (first match wins) — add yours here
    ├── ShaderPatchNames      ← every injected GLSL identifier (shared constants)
    ├── PatchToolkit          ← text surgery + shared GLSL snippets (tintSwapBlock…)
    ├── ComplementaryFamily   ← reference: fragment-stage lighting family
    └── MakeUpFamily          ← reference: vertex-stage lighting family
```

Two patch entry points call into this (via `IrisShaderCompat`):
- `IrisSodiumProgramsMixin` — Sodium terrain programs. The decoded RGB arrives as a
  custom vertex attribute; the core decodes it into `VERTEX_COLOR_NAME` (vertex-tint
  families) and/or the `VARYING_NAME` out-varying (fragment-tint families).
- `TransformPatcherMixin` — Iris's vanilla-format programs (entities, particles,
  block entities). Same decoded values, sourced from the overflowed `iris_UV2` halves.

## Step by step

1. **Classify the pack.** Unpack it and find where block light gets its color:
   - Color decided in the **fragment** stage (a `blocklightCol`-like value consumed by
     the lighting call) → fragment-tint family; model after `ComplementaryFamily`.
   - Color decided in the **vertex** stage (an out-varying like `candleColor` carrying
     shaped light to the fragment) → vertex-tint family; model after `MakeUpFamily`.
   - Color reconstructed in **composite/deferred** passes from a lightmap texture →
     hard mode: the varying approach cannot reach it; you would need a spare gbuffer
     channel. Open an issue before attempting.
   - The pack has its **own voxel colored lighting** (internal CL, Rethinking Voxels):
     source-surgery tinting is the wrong integration; do not add a family for it.

2. **Write the detection idiom** (`matchesFragmentTint` / `matchesVertexTint`): a
   `contains`/regex match on something structural and stable across the pack's
   versions (a function signature, a well-known variable declaration). Too broad and
   you will claim other packs' programs — the registry is first-match-wins, ordered in
   `ShaderFamilies`.

3. **Choose the metric for `PatchToolkit.tintSwapBlock`** — this decides whether hue
   affects brightness:
   - **LUMA** (`preserveLuma = true`) when the pack multiplies the hue straight into
     its lighting with no clamp: equal light level then renders equally bright for
     every hue (Complementary's case).
   - **PEAK** (`false`) when the pack clamps the value afterwards (MakeUp's 0..4
     candle clamp): luminance-preserving saturated hues would push single channels
     3–9× higher and clip. Peak is the only clip-safe invariant there.

4. **Patch rules (the `ShaderFamily` contract):**
   - *Fail closed*: if an idiom half-matches, return the input **unchanged** — an
     unpatched program is correct-but-white; a half-patched one may not compile.
   - *Idempotent*: guard on a marker identifier you inject
     (see `MakeUpFamily.MARKER`), so re-patching is a no-op.
   - *Namespaced*: every injected identifier uses `ShaderPatchNames` or the
     `colorfulLightingSodiumCompat_` prefix.
   - Insert declarations with `PatchToolkit.insertAfterVersion` (it keeps
     `#extension`/`#pragma` prologues legal).

5. **Held-item light (optional).** The uniforms are already registered globally:
   `HeldColor`/`HeldColor2` (vec3), `HeldLevel`/`HeldLevel2` (int), and
   `HeldAuthority`/`HeldAuthority2` (int). Honor the authority contract: when
   `HeldAuthority == 0` the mod has no opinion on that hand — **keep the pack's own
   held-light value** so its `item.properties` definitions still work; when `1`, the
   mod's level/color are final (including "deliberately dark": level 0).

6. **Dynamic entity lights (optional).** Per-slot uniforms
   `DynLight0..7`/`DynLightColor0..7`/`DynLightCount` exist; injecting the per-pixel
   hook needs pack-specific anchors (a camera-relative position and a mutable
   lightmap local in scope) — see `ComplementaryFamily.dynamicLightHook` for the
   falloff semantics to replicate (level minus one per block, max-combined, hue as a
   level-weighted blend).

7. **Tests.** Shader-pack sources are copyrighted — **never commit them**. Add
   synthetic sources that mimic the pack's idioms to
   `src/test/java/dev/colorfullighting/compat/PackedLightCompatTest.java` (see the
   existing `makeUpVertex`/Complementary shells) asserting: detection, injected
   markers, ordering of your hooks relative to pack statements, and idempotency.
   Then validate locally against the real pack: unzip it, run your patch over the
   actual sources, and eyeball the diff (compile-check with `glslangValidator` if
   available). List what you verified in the PR.

8. **In-game checklist** (with the real pack): placed colored light on terrain;
   held item colored (and a modded item *unknown* to the mod keeps the pack's own
   held light); a burning entity as a moving point light; water/translucent surfaces;
   a dimension switch (per-pipeline patch state); and the startup log line reporting
   the expected sanitized/tinted program counts.

## What you never need to touch

`IrisShaderCompat` (core decode/routing), the mixins, `IrisPatchState`, the uniform
registrations, and the CPU fallback all stay as-is for a new family. If a family
seems to require changing them, raise it first — that usually means the pack belongs
to the "hard mode" category above.
