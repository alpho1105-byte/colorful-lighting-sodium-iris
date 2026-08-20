# Colorful Lighting: Sodium/Iris Edition

A fork of [erykczy's Colorful Lighting](https://github.com/erykczy/colorful-lighting) (MIT) that makes colored
lighting work with **Sodium** and **Iris shader packs**, and fixes a number of upstream rendering and
light-propagation defects at the source. One jar replaces both the original `colorful_lighting` jar and the
separate `colorful_lighting_sodium_compat` jar.

![a creeper in a cave lit by lava](https://cdn.modrinth.com/data/cached_images/db98e8b5f28311e2c7edcd6e9cd00a82ba62f22b_0.webp)

## Features

- Blocks emit configurable colored light; stained glass filters the light passing through it
- Colors are data-driven: resource packs (or other mods) define emitters and filters in JSON
- Client side only — join any server, remove the mod at any time without touching the world
- **Sodium terrain support**: colored light is carried through Sodium's compact chunk vertex format
- **Iris shader-pack support**: compatible packs (Complementary and MakeUp/E-LITE families) get colored
  block light injected into their own lighting, including entities, block entities, particles, and
  held-item light; other packs are automatically sanitized so nothing renders black or fullbright —
  they show correct vanilla-brightness light without the tint. Adding tint support for another pack
  family is a self-contained plugin class: see [docs/ADDING_SHADER_SUPPORT.md](docs/ADDING_SHADER_SUPPORT.md)
- Works with light-engine replacements such as ScalableLux/Starlight (block updates are fed from the
  client level, not from vanilla light-engine internals)

## Versions & compatibility

| | Tested version | Notes |
|---|---|---|
| Minecraft | **1.21.1** | NeoForge 21.1.x (tested with 21.1.241) |
| [Sodium](https://www.curseforge.com/minecraft/mc-mods/sodium) | **0.8.13-beta.1** | *Optional.* Minimum `[0.8.13-beta.1,)`; no declared upper bound |
| [Iris](https://www.curseforge.com/minecraft/mc-mods/irisshaders) | **1.8.14-beta.1** | *Optional*, requires Sodium. Minimum `[1.8.14-beta.1,)`; no declared upper bound |
| [ScalableLux](https://www.curseforge.com/minecraft/mc-mods/scalablelux) | 0.3.0-alpha.0.6 (tested) | *Optional.* Works with or without it |
| [Sodium Extra](https://www.curseforge.com/minecraft/mc-mods/sodium-extra) | 0.9.3 (tested) | *Optional* |


The versions listed above are the exact ones I tested against. While newer versions of Sodium and Iris are allowed and recommended—as they may include bug fixes for issues not covered in my tests—they haven't been practically tested yet. If you encounter any unexpected issues on newer builds, please try downgrading to the specified versions above.

Sodium and Iris are detected at runtime: without them the mod uses the vanilla core-shader path (like upstream); with Sodium alone, terrain uses the compact-vertex path; with Sodium + Iris, the shader-pack integration activates as well.

[Complementary Shaders — Unbound](https://modrinth.com/shader/complementary-unbound) r5.8.1 (Reimagined
uses the same lighting core), MakeUp Ultra Fast 9.5d, and E-LITE 5.1.1. Packs without the recognized hooks
fall back to correct-but-uncolored light.

## Adding new colored light sources

Colors are regular resource-pack data — no code needed. In any namespace of your resource pack (or mod
resources), create a `light` folder next to `textures`/`models`:

**`assets/<namespace>/light/emitters.json`** — what light color a block emits:

```json
{
	"minecraft:torch": "#00FF00",
	"minecraft:red_candle": "red",
	"minecraft:soul_torch": "purple;5",
	"minecraft:oak_leaves": "light_blue;F"
}
```

- Color values: `"#RRGGBB"` hex (the canonical form) or a dye name (`"red"`, `"light_blue"`, ...).
- An optional `;X` suffix — a **single hex digit `0`–`F`** (`a` = 10, `f` = 15; decimal `;10` is
  rejected with a log warning) — overrides the emitted light *level*; without it the block's vanilla
  emission level is used. This lets non-emitting blocks (like the leaves above) emit light.
- Blocks that emit light but have no entry glow white.
- *Deprecated:* an `[r, g, b]` array also parses (integers on the 0–255 scale rounded to the internal
  0–15 range, floats on 0.0–1.0; optional 4th element = brightness on the same 0–255/0.0–1.0 scale)
  but logs a deprecation warning — prefer the hex string.

**Blockstate-specific colors** *(this fork)* — keys may carry blockstate properties using command syntax,
which is how a block whose ID never changes (e.g. a lamp re-dyed by right-clicking) gets a different light
color per state:

```json
{
	"minecraft:trial_spawner[ominous=true]": "#90fbff",
	"yourmod:oil_lamp[color=red]": "red",
	"yourmod:oil_lamp[color=lime]": "#80FF00",
	"yourmod:oil_lamp[lit=false]": "black;0"
}
```

- Only the listed properties need to match; unlisted properties are wildcards. The most specific matching
  entry wins, and a plain `modid:block` entry is the fallback for that block.
- Properties and values are validated against the block at load time — typos are logged and skipped.
- State changes are ordinary block updates, so re-dyeing a lamp re-propagates its light immediately.

**`assets/<namespace>/light/filters.json`** — what light color passes through a block (same color syntax):

```json
{
	"minecraft:red_stained_glass": "#FF0000",
	"minecraft:green_stained_glass": "red",
	"minecraft:glass": "#00FFFF"
}
```

Entries from every loaded pack and mod are merged (higher packs win), and `F3 + T` reloads and re-lights the
world — convenient for iterating on colors. Mods can ship the same files in their own resources to register
their blocks; see erykczy's [colorful-glowstone](https://github.com/erykczy/colorful-glowstone) example mod.

## Entity light sources

*(this fork)* Entities can emit colored light too — the dynamic-lights approach (as popularized by
LambDynamicLights) running on the colored engine: each client tick the mod tracks every visible entity's
light and re-propagates when its position, level, or color changes. Purely a client-side visual; vanilla
light values and gameplay are untouched.

> **Known tradeoff:** without a shader pack, moving lights use smooth distance falloff sampled from the
> source's exact position and are **not occluded by blocks** (light can show through a thin wall) — the
> same behavior as LambDynamicLights. The alternative, routing moving sources through the block engine,
> restores occlusion but brings back a visible re-propagation flicker whenever a source crosses a block
> boundary; this fork deliberately keeps the smooth path.

**Static per-type colors** — `assets/<namespace>/light/entity_emitters.json`, same color syntax as block
emitters — `"#RRGGBB"` or a dye name, plus an optional `;X` level suffix (single hex digit `0`–`F`;
missing suffix means full level 15):

```json
{
	"minecraft:glow_squid": "#61f2d0;7",
	"yourmod:will_o_wisp": "cyan;9"
}
```

Shipped defaults: glow squids, blazes, magma cubes, and allays glow in their own colors, and **any burning
entity casts fire-colored light**.

**Stateful lights (Java API)** — when the light depends on entity state, register a provider; it is called
every client tick, so returning a different value simply moves/re-colors the light:

```java
import me.erykczy.colorfullighting.api.*;

EntityLightSources.register(MyEntities.LANTERN_SPIRIT.get(), entity -> {
    LanternSpirit spirit = (LanternSpirit) entity;
    if (!spirit.isLit()) return null;                            // no light
    return EntityLight.fromDye(spirit.getDyeColor(), 11);        // dye name...
    // or: EntityLight.fromHex("#80FF00", 11) / EntityLight.fromRGB8(128, 255, 0, 11)
});
```

A registered provider replaces the JSON entry for that type. Providers run on the client thread and should
be cheap, read-only lookups of synched entity data.

## Changes over upstream 1.3.0

- Merged Sodium/Iris compatibility layer (formerly a separate mod), gated by a mixin plugin so it is inert
  when those mods are absent
- Freshly placed or broken light sources update immediately under any light engine; no more shader reload
- Correct trilinear entity-light sampling (smooth falloff instead of popping at range edges)
- Emissive blocks (magma, etc.) render fullbright again; renderer light overrides (glow squid dimming,
  fullbright projectiles, modded renderers) are respected; item frames no longer glow in the dark
- Ghost-light fixes for rapid place/break and multi-source removal; atomic light storage (no more torn
  colors baked into meshes); resilient, named, daemonized propagation thread
- Config robustness: malformed entries can no longer abort the whole parse; dim hex colors are no longer
  rounded down to black; NPE guards on early ticks and the startup resource reload
- 2.4.0 hardening pass (full-codebase review): thread-safe publication of config maps and the propagation
  staging buffer; mixed-format AO blends no longer tint seams red at colored-data boundaries; translucent
  quad splitting no longer reuses stale colored light; removing a source in a view-border chunk no longer
  leaves permanent ghost light; colored sampling reaches the build-height limits; per-dimension shader
  pipelines track their patch state independently; a burning player casts light under shader packs; held
  items unknown to the mod keep the shader pack's own held-light definition; the config screen's Apply no
  longer silently re-enables disabled entries; config corruption always creates a fresh backup; 2.3.x
  `colorful_lighting-client.toml` entity light levels migrate into the v2 config; one canonical hex codec
  behind every color surface; a startup log line states the detected Sodium/Iris versions and whether the
  compat mixins actually applied

## Building

Gradle 9 / Java 21 / ModDevGradle. The compatibility layer compiles against renderer jars that are **not**
included in this repository (Sodium's license does not permit redistribution). Create a `libs/` folder in
the project root and put these in it:

1. [Iris](https://www.curseforge.com/minecraft/mc-mods/irisshaders) `1.8.14-beta.1+mc1.21.1` (NeoForge) —
   the downloaded jar as-is
2. From the [Sodium](https://www.curseforge.com/minecraft/mc-mods/sodium) `0.8.13-beta.1+mc1.21.1`
   (NeoForge) download, extract everything under `META-INF/jarjar/` inside the jar (open it as a zip):
   the `net.caffeinemc.sodium-neoforge-...-mod.jar` implementation jar and the four bundled `fabric-*.jar`
   API modules

Then run `gradlew build`; the mod jar lands in `build/libs/`.

## License

MIT — see [LICENSE.txt](LICENSE.txt). Copyright (c) 2025 erykczy (original mod); fork additions
copyright (c) 2026 the Sodium/Iris Edition contributors, released under the same license. The upstream
copyright notice and license text are preserved in the jar and in this repository.

## Credits

- [erykczy (thecode)](https://github.com/erykczy) — the original Colorful Lighting
  ([CurseForge](https://www.curseforge.com/minecraft/mc-mods/colorful-lighting) ·
  [Modrinth](https://modrinth.com/mod/colorful-lighting))
- The Sodium, Iris, and ScalableLux teams for the renderer stack this fork integrates with
