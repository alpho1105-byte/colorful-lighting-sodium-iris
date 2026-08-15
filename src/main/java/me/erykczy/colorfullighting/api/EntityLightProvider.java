package me.erykczy.colorfullighting.api;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Computes the light an entity emits right now. Called once per client tick for every
 * visible entity of the registered type, so the returned light can follow any entity
 * state (a dyed collar, an "angry" flag, synched entity data, ...). Return null (or an
 * {@link EntityLight#isDark() dark} light) for no emission.
 *
 * <p>Called on the client thread; keep it cheap and read-only.
 */
@FunctionalInterface
public interface EntityLightProvider {
    @Nullable
    EntityLight getLight(Entity entity);
}
