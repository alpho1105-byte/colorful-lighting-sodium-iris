package dev.colorfullighting.compat.mixin;

import net.caffeinemc.mods.sodium.client.checks.ResourcePackScanner;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.stream.Stream;

@Mixin(value = ResourcePackScanner.class, remap = false)
abstract class ResourcePackScannerMixin {
    private static final String COLORFUL_LIGHTING_PACK_ID = "mod/colorful_lighting";

    @Redirect(
            method = "checkIfCoreShaderLoaded",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/ResourceManager;listPacks()Ljava/util/stream/Stream;"
            )
    )
    private static Stream<PackResources> colorfulLightingSodiumCompat$skipHandledPack(
            ResourceManager resourceManager
    ) {
        return resourceManager.listPacks()
                .filter(pack -> !COLORFUL_LIGHTING_PACK_ID.equals(pack.packId()));
    }
}
