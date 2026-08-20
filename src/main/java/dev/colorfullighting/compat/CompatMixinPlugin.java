package dev.colorfullighting.compat;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the compatibility-layer mixins on the renderer mods actually being installed, so
 * the merged jar also runs standalone or with only part of the supported renderer stack.
 */
public class CompatMixinPlugin implements IMixinConfigPlugin {
    private boolean sodiumLoaded;
    private boolean irisLoaded;
    private boolean sableLoaded;
    private boolean flywheelLoaded;
    private boolean createLoaded;
    private boolean veilLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        sodiumLoaded = FMLLoader.getLoadingModList().getModFileById("sodium") != null;
        irisLoaded = FMLLoader.getLoadingModList().getModFileById("iris") != null;
        sableLoaded = FMLLoader.getLoadingModList().getModFileById("sable") != null;
        flywheelLoaded = FMLLoader.getLoadingModList().getModFileById("flywheel") != null;
        createLoaded = FMLLoader.getLoadingModList().getModFileById("create") != null;
        veilLoaded = FMLLoader.getLoadingModList().getModFileById("veil") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        // explicit per-mixin routing; the table lives in CompatMixinRouting (dependency
        // free) so the test harness verifies it against the mixin config at build time
        String modId = CompatMixinRouting.MIXIN_MODS.get(simpleName);
        if (modId == null) {
            LogUtils.getLogger().warn(
                    "Mixin {} is not registered in CompatMixinRouting;"
                            + " gating it on Sodium as a fallback",
                    mixinClassName
            );
            return sodiumLoaded;
        }
        return switch (modId) {
            case CompatMixinRouting.SODIUM -> sodiumLoaded;
            case CompatMixinRouting.IRIS -> irisLoaded;
            case CompatMixinRouting.SABLE -> sableLoaded;
            case CompatMixinRouting.FLYWHEEL -> flywheelLoaded;
            case CompatMixinRouting.CREATE -> createLoaded;
            case CompatMixinRouting.VEIL -> veilLoaded;
            default -> sodiumLoaded;
        };
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
