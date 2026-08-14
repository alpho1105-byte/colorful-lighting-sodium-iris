package me.erykczy.colorfullighting.mixin.render;

import dev.colorfullighting.compat.ColorfulLightGate;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.PackedLightData;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    private static final Set<EntityType<?>> FIRE_TINT_ENTITIES = Set.of(
            EntityType.BLAZE,
            EntityType.MAGMA_CUBE
    );

    @Shadow protected abstract int getBlockLightLevel(Entity entity, BlockPos pos);
    @Shadow protected abstract int getSkyLightLevel(Entity entity, BlockPos pos);

    @Inject(method = "getPackedLightCoords", at = @At("HEAD"), cancellable = true)
    private <T extends Entity>void colorfullighting$getPackedLightCoords(T entity, float partialTicks, CallbackInfoReturnable<Integer> cir) {
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        if(engine == null) return; // vanilla path until the engine exists
        BlockPos blockpos = BlockPos.containing(entity.getLightProbePosition(partialTicks));
        int skyLight = getSkyLightLevel(entity, blockpos);
        ColorRGB8 color = engine.sampleTrilinearLightColor(entity.getLightProbePosition(partialTicks));

        // keep calling the renderer's own light virtuals so vanilla and modded overrides
        // (glow squid dimming, fullbright projectiles, ...) survive: any brightness the
        // renderer reports beyond the level's actual block light becomes a boost, fire-
        // tinted for burning entities, white otherwise
        int rendererLight = getBlockLightLevel(entity, blockpos);
        int levelLight = entity.level().getBrightness(LightLayer.BLOCK, blockpos);
        if(rendererLight > levelLight) {
            boolean fireTint = entity.isOnFire() || FIRE_TINT_ENTITIES.contains(entity.getType());
            ColorRGB8 boost = fireTint
                    ? ColorRGB8.fromRGB4(Config.getLightColor(Blocks.FIRE.builtInRegistryHolder().getKey())).mul(rendererLight / 15.0f)
                    : ColorRGB8.fromRGB8(rendererLight * 17, rendererLight * 17, rendererLight * 17);
            color = ColorRGB8.fromRGB8(
                    Math.max(color.red, boost.red),
                    Math.max(color.green, boost.green),
                    Math.max(color.blue, boost.blue)
            );
        }

        int packedLight = PackedLightData.packData(skyLight, color);
        // while a shader pack whose programs could not all be sanitized is active, the
        // packed colorful value must be converted back to vanilla packed light on the CPU
        cir.setReturnValue(ColorfulLightGate.decodeForShaderPack(packedLight));
    }
}
