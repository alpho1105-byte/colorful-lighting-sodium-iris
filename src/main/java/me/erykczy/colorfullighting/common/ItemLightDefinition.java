package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.api.EntityLight;
import org.jetbrains.annotations.Nullable;

/** Resource-pack definition for an item light. Null light derives from a BlockItem. */
public record ItemLightDefinition(@Nullable EntityLight light, boolean waterSensitive) {
}
