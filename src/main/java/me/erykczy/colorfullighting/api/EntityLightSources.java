package me.erykczy.colorfullighting.api;

import me.erykczy.colorfullighting.common.EntityLightManager;
import net.minecraft.world.entity.EntityType;

/**
 * Entry point for mods that want their entities to emit colored light. Purely a
 * client-side visual: nothing here affects vanilla light values or gameplay.
 *
 * <p>Static per-type colors can be declared without code in
 * {@code assets/<namespace>/light/entity_emitters.json}; register a provider here when
 * the light depends on entity state. A registered provider replaces the JSON entry for
 * that type. Burning entities additionally emit fire-colored light out of the box.
 */
public final class EntityLightSources {
    private EntityLightSources() {
    }

    /** Register (or replace) the light provider for an entity type. Safe to call from mod init. */
    public static void register(EntityType<?> type, EntityLightProvider provider) {
        EntityLightManager.registerProvider(type, provider);
    }
}
