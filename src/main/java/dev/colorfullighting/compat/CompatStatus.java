package dev.colorfullighting.compat;

import dev.colorfullighting.compat.sodium.SodiumVertexProbe;
import me.erykczy.colorfullighting.ColorfulLighting;
import net.neoforged.fml.ModList;

/**
 * One startup line that makes any user log self-explanatory: the mod's own version,
 * which renderer stack was detected, and whether the Sodium compatibility mixins
 * actually applied. Past bug reports were undiagnosable because none of this ever
 * appeared in the log (a foreign build or a silently skipped mixin set looks
 * identical to a healthy install otherwise).
 */
public final class CompatStatus {
    private CompatStatus() {
    }

    public static void logStartupSummary() {
        String sodium = describeSodium();
        ColorfulLighting.LOGGER.info(
                "Colorful Lighting Sodium/Iris Edition {} | Sodium: {} | Iris: {}",
                modVersion(ColorfulLighting.MOD_ID),
                sodium,
                modVersion("iris")
        );
        if (sodiumPresent() && !compatMixinsApplied()) {
            ColorfulLighting.LOGGER.warn(
                    "Sodium is installed but the Colorful Lighting compatibility mixins"
                            + " were NOT applied - terrain lighting will be wrong (dark"
                            + " world in daylight). Check earlier log lines for a mixin"
                            + " failure, or a Sodium build this version does not support."
            );
        }
    }

    private static String describeSodium() {
        if (!sodiumPresent()) {
            return "absent (vanilla core-shader path)";
        }
        return modVersion("sodium")
                + (compatMixinsApplied() ? " (compat active)" : " (COMPAT MIXINS NOT APPLIED)");
    }

    private static boolean sodiumPresent() {
        return ModList.get().isLoaded("sodium");
    }

    private static boolean compatMixinsApplied() {
        // isolated in its own class: the probe touches a Sodium type and must never
        // load when Sodium is absent
        return SodiumVertexProbe.chunkVertexExtended();
    }

    private static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("absent");
    }
}
