> [!NOTE]
> **Sodium/Iris Edition** — a local fork of [erykczy's Colorful Lighting](https://github.com/erykczy/colorful-lighting) (MIT).
> This fork merges the `colorful_lighting_sodium_compat` layer into the mod (Sodium 0.8 terrain vertex format,
> Iris 1.8 shader-pack sanitizing/tinting, colored held-item light) and fixes upstream defects at the source:
> emissive-block fullbright, entity light overrides, trilinear sampling, ghost light on rapid place/break,
> light-engine-replacement compatibility (ScalableLux/Starlight), storage tearing, worker-thread resilience,
> and config parse robustness. Sodium 0.8.13-beta.1 and Iris 1.8.14-beta.1 are OPTIONAL: the compatibility
> layer detects them and activates only when present (standalone and Sodium-only setups use the vanilla
> core-shader path). When they are installed, the versions must match the pinned ranges. Replaces BOTH the
> original `colorful_lighting` jar and the separate compat jar.

> [!CAUTION]
> (Upstream notice) Due to my busy schedule, **the project's development is slowed down**.

![a creeper in a cave lit by lava](https://cdn.modrinth.com/data/cached_images/db98e8b5f28311e2c7edcd6e9cd00a82ba62f22b_0.webp)
The mod adds colored lighting to the game. Other mods that add colored lights can use it as a dependency.\
The mod is not compatible with Sodium!

# Features
- Different blocks can emit different colors
- Light that passes through stained glass is also colored
- Emitted colors, and filtered colors can be customized in resource packs
- The mod is client side - you can play with it on any server and you won't have any problems removing it from your world

# Resource Pack Tutorial
In your resourcepack's namespace folder (where folders like `textures` and `models` are located) create a `light` folder. There, you can create an `emitters.json` file, which defines what light colors blocks emit. Example:
\
_assets\\example\\light\\emitters.json_
```json
{
	"minecraft:torch": "#00FF00", // color in hex
	"minecraft:red_candle": "red", // dye name
	"minecraft:redstone_lamp": [ 0, 255, 255 ],
	"minecraft:soul_torch": "purple;5", // override light level emission
	"minecraft:oak_leaves": "light_blue;F" // value after ';' is a hex number from 0 to F
}
```
You can also create `filters.json`, where you define what light color passes through a given block. Example:\
_assets\\example\\light\\filters.json_
```json
{
	"minecraft:red_stained_glass": "#00FF00", // color in hex
	"minecraft:green_stained_glass": "red", // dye name
	"minecraft:glass": [ 0, 255, 255 ]
}
```

# Compatible Resource Packs
~~Colorful Candles: [colorful-candles.zip](https://github.com/erykczy/colorful-lighting/raw/e372648afdd442e96340f0d8ee477d6ae8138739/addons/colorful-candles.zip)~~ (colorful candles are now enabled by default)

# Example Mods
Colorful Glowstone:
https://github.com/erykczy/colorful-glowstone