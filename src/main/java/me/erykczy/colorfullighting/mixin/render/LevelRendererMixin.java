package me.erykczy.colorfullighting.mixin.render;

import me.erykczy.colorfullighting.ColorfulLighting;
import dev.colorfullighting.compat.ColorfulLightGate;
import me.erykczy.colorfullighting.accessors.BlockStateWrapper;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.PackedLightData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private static void colorfullighting$getLightColor(BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        if(engine == null || !ColorfulLightGate.canSampleColorful(level, pos)) return;
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        if(state.emissiveRendering(level, pos)) {
            LevelAccessor levelAccessor = ColorfulLighting.clientAccessor.getLevel();
            if(levelAccessor == null) {
                cir.setReturnValue(PackedLightData.packData(15, 255, 255, 255));
                return;
            }
            var emission = Config.getLightColor(new BlockStateWrapper(state));
            cir.setReturnValue(PackedLightData.packData(skyLight, ColorRGB8.fromRGB4(emission)));
            return; // previously fell through and the engine sample overwrote the emissive value
        }

        ColorRGB4 color = engine.sampleLightColor(pos);
        // vanilla floors the result at the state's own emission; without it dynamically
        // emitting blocks go dark and fresh sources depend entirely on async propagation
        int emission = state.getLightEmission(level, pos);
        if(emission > 0) {
            ColorRGB4 emitColor = Config.getLightColor(new BlockStateWrapper(state)).mul(emission / 15.0f);
            color = ColorRGB4.fromRGB4(
                    Math.max(color.red4, emitColor.red4),
                    Math.max(color.green4, emitColor.green4),
                    Math.max(color.blue4, emitColor.blue4)
            );
        }
        cir.setReturnValue(PackedLightData.packData(skyLight, ColorRGB8.fromRGB4(color)));
    }

}
