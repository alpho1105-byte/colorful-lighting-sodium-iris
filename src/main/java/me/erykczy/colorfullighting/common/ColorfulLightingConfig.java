package me.erykczy.colorfullighting.common;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config, editable from the mods menu (NeoForge's built-in configuration
 * screen). Light levels of the built-in entity lights; 0 disables that light.
 */
public final class ColorfulLightingConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue BURNING_ENTITY_LIGHT;
    public static final ModConfigSpec.IntValue GLOW_SQUID_LIGHT;
    public static final ModConfigSpec.IntValue BLAZE_LIGHT;
    public static final ModConfigSpec.IntValue MAGMA_CUBE_LIGHT;
    public static final ModConfigSpec.IntValue ALLAY_LIGHT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("entity_lights");
        BURNING_ENTITY_LIGHT = builder
                .comment("Light level of burning entities (LambDynamicLights uses 15). 0 disables.")
                .defineInRange("burningEntityLightLevel", 15, 0, 15);
        GLOW_SQUID_LIGHT = builder
                .comment("Light level of glow squids. 0 disables.")
                .defineInRange("glowSquidLightLevel", 7, 0, 15);
        BLAZE_LIGHT = builder
                .comment("Light level of blazes. 0 disables.")
                .defineInRange("blazeLightLevel", 10, 0, 15);
        MAGMA_CUBE_LIGHT = builder
                .comment("Light level of magma cubes. 0 disables.")
                .defineInRange("magmaCubeLightLevel", 8, 0, 15);
        ALLAY_LIGHT = builder
                .comment("Light level of allays. 0 disables.")
                .defineInRange("allayLightLevel", 5, 0, 15);
        builder.pop();
        SPEC = builder.build();
    }

    private ColorfulLightingConfig() {
    }
}
