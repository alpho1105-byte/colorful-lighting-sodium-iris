package dev.colorfullighting.compat.iris;

import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.joml.Vector3f;

/**
 * Supplies the Colorful Lighting emitter color of each held item to the shader-pack
 * uniforms registered in IrisIdMapUniformsMixin. Zero means "no colorful data" and the
 * patched GetHeldLighting falls back to the pack's own held-light color. A held block
 * reports the same color the engine would emit if it were placed; non-block items have
 * no emitter entry and the pack's uniform-driven brightness is left to decide.
 */
public final class HeldLightColors {
    private HeldLightColors() {
    }

    public static Vector3f mainHand() {
        return colorFor(InteractionHand.MAIN_HAND);
    }

    public static Vector3f offHand() {
        return colorFor(InteractionHand.OFF_HAND);
    }

    private static Vector3f colorFor(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new Vector3f();
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return new Vector3f();
        }

        Block block = blockItem.getBlock();
        if (block.defaultBlockState().getLightEmission() <= 0) {
            return new Vector3f();
        }

        try {
            ColorRGB4 color = Config.getLightColor(block.builtInRegistryHolder().getKey());
            return new Vector3f(color.red4 / 15.0F, color.green4 / 15.0F, color.blue4 / 15.0F);
        } catch (RuntimeException beforeConfigLoaded) {
            return new Vector3f();
        }
    }
}
