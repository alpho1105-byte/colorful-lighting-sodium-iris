package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.PackedLightCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Flerovium fast-paths SimpleBakedModel items straight into Sodium's vertex buffer
// and merges the baked emissive lightmap with the incoming packed light via a signed
// integer Math.max. Colorful Lighting's packed format carries the 0xF marker in the
// top nibble, making the value NEGATIVE - the max always picks the baked side
// (usually 0) and the item renders pitch black. Handing Flerovium the vanilla
// equivalent up front keeps its emissive logic intact; the conversion is a no-op for
// vanilla-format values. Tradeoff: items on this optimized path lose the colored
// tint (correct brightness, neutral hue) - same policy as the translucent-split
// fallback in ChunkVertexMixin.
@Mixin(targets = {
        "com.moepus.flerovium.functions.FastSimpleBakedModelRenderer",
        "com.moepus.flerovium.Iris.IrisSimpleBakedItemRenderer"
}, remap = false)
abstract class FleroviumItemRendererMixin {
    // render(SimpleBakedModel, int cullMask, ItemStack, int packedLight, int overlay,
    // PoseStack, VertexBufferWriter, ItemColors) - the light is the second int
    @ModifyVariable(method = "render", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private static int colorfulLightingSodiumCompat$decodePackedLight(int packedLight) {
        return PackedLightCompat.toVanilla(packedLight);
    }
}
