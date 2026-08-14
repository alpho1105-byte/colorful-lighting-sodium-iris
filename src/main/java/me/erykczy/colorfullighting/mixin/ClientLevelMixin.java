package me.erykczy.colorfullighting.mixin;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Feeds block changes to the colored light engine. setBlocksDirty is a vanilla
// notification every client-side block change passes through, independent of the
// light-engine implementation — unlike hooks inside BlockLightEngine/LevelLightEngine,
// which light-engine replacements such as ScalableLux (Starlight) construct-and-discard
// or override without calling super, silently starving the engine of updates.
@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void colorfullighting$onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if(oldState == newState || ColorfulLighting.clientAccessor == null) return;
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        if(engine == null) return;
        engine.onBlockLightPropertiesChanged(pos.immutable());
    }
}
