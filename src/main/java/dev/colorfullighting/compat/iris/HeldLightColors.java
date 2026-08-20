package dev.colorfullighting.compat.iris;

import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.common.EntityLightManager;
import me.erykczy.colorfullighting.common.EntityLightManager.HeldLightDecision;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.joml.Vector3f;

/**
 * Supplies each held item's Colorful Lighting emitter color, level, and authority to
 * the shader-pack uniforms registered in IrisIdMapUniformsMixin. Resolution is shared
 * with the engine-backed item lights, including non-block resource definitions. The
 * patched shader treats an authoritative hand as final (a zero level then means no
 * held light), while a non-authoritative hand - an item the mod knows nothing about -
 * keeps the pack's own held-light definition.
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

    public static int mainHandLevel() {
        return levelFor(InteractionHand.MAIN_HAND);
    }

    public static int offHandLevel() {
        return levelFor(InteractionHand.OFF_HAND);
    }

    public static int mainHandAuthority() {
        return decisionFor(InteractionHand.MAIN_HAND).authoritative() ? 1 : 0;
    }

    public static int offHandAuthority() {
        return decisionFor(InteractionHand.OFF_HAND).authoritative() ? 1 : 0;
    }

    private static HeldLightDecision decisionFor(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return HeldLightDecision.DEFER;
        }

        return EntityLightManager.heldLightDecision(
                player.getItemInHand(hand),
                player.isUnderWater()
        );
    }

    private static Vector3f colorFor(InteractionHand hand) {
        EntityLight light = decisionFor(hand).light();
        if (light == null) return new Vector3f();
        return new Vector3f(
                light.red4() / 15.0F,
                light.green4() / 15.0F,
                light.blue4() / 15.0F
        );
    }

    private static int levelFor(InteractionHand hand) {
        EntityLight light = decisionFor(hand).light();
        return light == null ? 0 : light.brightness4();
    }
}
