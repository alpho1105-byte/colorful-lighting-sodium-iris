package dev.colorfullighting.compat;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the compatibility-layer mixins on the renderer mods actually being installed, so
 * the merged jar also runs standalone or with Sodium alone. Iris-prefixed mixins (and the
 * TransformPatcher hook) need Iris; everything else in this config targets Sodium.
 */
public class CompatMixinPlugin implements IMixinConfigPlugin {
    private boolean sodiumLoaded;
    private boolean irisLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        sodiumLoaded = FMLLoader.getLoadingModList().getModFileById("sodium") != null;
        irisLoaded = FMLLoader.getLoadingModList().getModFileById("iris") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (simpleName.startsWith("Iris") || simpleName.equals("TransformPatcherMixin")) {
            return irisLoaded;
        }
        return sodiumLoaded;
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
