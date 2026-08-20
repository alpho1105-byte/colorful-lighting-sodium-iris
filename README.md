# Colorful Lighting: Sodium/Iris Edition

A fork of [erykczy's Colorful Lighting](https://github.com/erykczy/colorful-lighting) (MIT) that makes colored
lighting work with **Sodium** and **Iris shader packs**, adds dynamic entity/item lights and an in-game light
editor, and fixes a number of upstream rendering and light-propagation defects at the source.

## Features

- **Colored block light** with per-block colors and stained-glass-style filters, propagated by a dedicated
  client-side engine — vanilla light values and gameplay are untouched
- **Dynamic entity & item lights** on the colored engine (the LambDynamicLights idea, in color): burning
  entities, glow squids, dropped/held/framed items, and a Java API for stateful entity lights
- **In-game light editor** (the mod's config screen): recolor, re-level, or disable any block, item, entity,
  or the burning-entity light without leaving the game, and export your overrides as a resource pack
- **Data-driven colors**: resource packs or mods define emitters/filters in JSON, including
  blockstate-specific entries (`yourmod:lamp[color=red]`); `F3 + T` hot-reloads them
- **Sodium terrain support**: colored light rides Sodium's compact chunk vertex format
- **Iris shader-pack support**: supported pack families get colored block, entity, particle, held-item, and
  per-pixel moving lights injected into their own lighting (see the table below); unsupported packs are
  automatically sanitized and render correct vanilla-brightness light without the tint
- **Renderer integrations**: Create contraptions (moving colored lights), Flywheel instancing, Sable
  sublevels, Veil
- Client side only — join any server, remove the mod at any time without touching the world

## Versions & requirements

| | Version | Notes |
|---|---|---|
| Minecraft | **1.21.1** | NeoForge 21.1.x (tested with 21.1.241) |
| [Sodium](https://modrinth.com/mod/sodium) | **0.8.13-beta.1** tested | *Optional.* Minimum `[0.8.13-beta.1,)` |
| [Iris](https://modrinth.com/mod/iris) | **1.8.14-beta.1** tested | *Optional*, requires Sodium. Minimum `[1.8.14-beta.1,)` |

Sodium and Iris are detected at runtime: without them the mod uses the vanilla core-shader path (like
upstream); with Sodium alone, terrain uses the compact-vertex path; with Sodium + Iris, the shader-pack
integration activates as well. A startup log line reports the detected versions and whether the
compatibility layer actually applied — include it in bug reports.

Optional mods the integrations were built and tested against (all detected at runtime, none required):
[ScalableLux](https://modrinth.com/mod/scalablelux) 0.3.0-alpha, Sodium Extra 0.9.3,
[Create](https://modrinth.com/mod/create) 6.0.10 (with its bundled Flywheel),
[Sable](https://modrinth.com/mod/sable) 2.0.4, [Veil](https://modrinth.com/mod/veil) 4.4.1.
Newer versions are allowed; if something breaks on a newer build, try the listed version first.

## Supported shader packs

[Complementary](https://modrinth.com/shader/complementary-unbound) Unbound / Reimagined 
[BSL](https://modrinth.com/shader/bsl-shaders) 
[MakeUp Ultra Fast](https://modrinth.com/shader/makeup-ultra-fast-shaders) 

Behavior shared by every supported pack:

- **Held items the mod knows nothing about keep the pack's own held-light definition** (`item.properties`),
  so pack-side item lights for other mods keep working; items the mod *does* know are authoritative,
  including "deliberately dark" (disabled overrides, a torch underwater).
- Packs with their own colored-light option (Complementary's internal CL, BSL's MULTICOLORED_BLOCKLIGHT)
  coexist: where Colorful Lighting has color data its hue wins, elsewhere the pack's own coloring applies.
- **Any other pack** falls back to correct-but-uncolored light — never black or fullbright terrain.
- LOD renderers (Voxy, Distant Horizons) draw far terrain from their own baked data: brightness is correct
  there, the color tint ends at the loaded-chunk boundary. This matches how the packs' own colored
  lighting treats LOD terrain.

Want another pack supported? Tint support is a self-contained plugin class — see
[docs/ADDING_SHADER_SUPPORT.md](docs/ADDING_SHADER_SUPPORT.md).

## Adding colored light sources (blocks)

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
  0–15 range, floats on 0.0–1.0; optional 4th element = brightness on the same scale) but logs a
  deprecation warning — prefer the hex string.

**Blockstate-specific colors** — keys may carry blockstate properties using command syntax, which is how a
block whose ID never changes (e.g. a lamp re-dyed by right-clicking) gets a different light color per state:

```json
{
	"minecraft:trial_spawner[ominous=true]": "#90fbff",
	"yourmod:oil_lamp[color=red]": "red",
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

## Entity & item lights

Entities and items can emit colored light too — tracked every client tick, moving smoothly with the source.
Shipped defaults: glow squids, blazes, magma cubes, and allays glow in their own colors, **any burning
entity casts fire-colored light**, and light-emitting blocks glow when held, dropped, framed, or displayed.

> **Known tradeoff:** without a shader pack, moving lights use smooth distance falloff sampled from the
> source's exact position and are **not occluded by blocks** (light can show through a thin wall) — the
> same behavior as LambDynamicLights. Routing moving sources through the block engine would restore
> occlusion but bring back a visible re-propagation flicker whenever a source crosses a block boundary;
> this fork deliberately keeps the smooth path. With a supported shader pack, moving lights render as
> smooth per-pixel point lights instead.

**Entities** — `assets/<namespace>/light/entity_emitters.json`, same color syntax as block emitters
(a missing `;X` level suffix means full level 15):

```json
{
	"minecraft:glow_squid": "#61f2d0;7",
	"yourmod:will_o_wisp": "cyan;9"
}
```

**Items** — `assets/<namespace>/light/item_emitters.json`. Items derived from light-emitting blocks glow
automatically; entries add non-block items, override the automatic color, or mark an item as going out
underwater:

```json
{
	"minecraft:glow_ink_sac": "#61f2d0;6",
	"minecraft:torch": { "water_sensitive": true },
	"yourmod:flashlight": { "light": "#f2eecb;C", "water_sensitive": false }
}
```

- A plain value defines a fixed item light; the object form adds `water_sensitive` (light goes out while
  underwater). Omitting `"light"` in the object form keeps the automatic block-derived color.

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

## In-game editor & configuration

Open the mod's config screen (Mods → Colorful Lighting → Config) to edit lights live, no files needed:

- **Blocks & items** and **Entities** categories, grouped into families (all beds, all candles, ...) with
  search; edit a whole family or a single member
- Recolor (hex input or palette), change the level (slider), **disable** a light, or reset to defaults —
  including the burning-entity light
- Changes apply immediately and persist to `config/colorful_lighting/lights.json`
- **Export** turns your overrides into a ready-to-share resource pack
- Applying values to a family never silently re-enables members you disabled individually; re-enabling is
  explicit (Reset)

Editor overrides layer on top of resource-pack definitions. 2.3.x `colorful_lighting-client.toml` entity
light levels migrate automatically on first run; corrupted config files are always backed up before a fresh
one is written.

## Building

Gradle 9 / Java 21 / ModDevGradle. The compatibility layer compiles against renderer jars that are **not**
included in this repository (Sodium's license does not permit redistribution). Create a `libs/` folder in
the project root and put these in it:

1. [Iris](https://modrinth.com/mod/iris) `1.8.14-beta.1+mc1.21.1` (NeoForge) — the downloaded jar as-is
2. From the [Sodium](https://modrinth.com/mod/sodium) `0.8.13-beta.1+mc1.21.1` (NeoForge) download, extract
   everything under `META-INF/jarjar/` inside the jar (open it as a zip): the
   `net.caffeinemc.sodium-neoforge-...-mod.jar` implementation jar and the four bundled `fabric-*.jar`
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
- The authors of Complementary, BSL, and MakeUp Ultra Fast / E-LITE — the shader packs this fork carries
  colored light into
