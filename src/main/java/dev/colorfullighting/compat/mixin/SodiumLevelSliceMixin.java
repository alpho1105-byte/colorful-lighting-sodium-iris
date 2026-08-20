package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.level.RenderLevelView;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Marks Sodium chunk-build snapshots as views of their live client level. */
@Mixin(value = LevelSlice.class, remap = false)
abstract class SodiumLevelSliceMixin implements RenderLevelView {
    @Shadow
    @Final
    private ClientLevel level;

    @Override
    public Object colorfulLighting$getRootLevel() {
        return level;
    }
}
