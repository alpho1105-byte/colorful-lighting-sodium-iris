package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.PackedLightCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Converts RGB packed light before Flywheel stores it as vanilla block/sky light. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.lib.instance.ColoredLitInstance", remap = false)
abstract class FlywheelColoredLitInstanceMixin {
    @ModifyVariable(method = "light", at = @At("HEAD"), argsOnly = true)
    private int colorfulLighting$useVanillaLightLayout(int packedLight) {
        return PackedLightCompat.toVanilla(packedLight);
    }
}
