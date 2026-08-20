package me.erykczy.colorfullighting.resourcemanager;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.EntityLightManager;
import me.erykczy.colorfullighting.common.ItemLightDefinition;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public class ConfigResourceManager implements ResourceManagerReloadListener {
    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Logger LOGGER = ColorfulLighting.LOGGER;

    /** A parsed entry key: plain "modid:block" or state-filtered "modid:block[prop=value,...]". */
    private record EntryKey(ResourceLocation block, Map<String, String> properties) {}

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        HashMap<ResourceLocation, Config.ColorEmitter> emitters = new HashMap<>();
        HashMap<ResourceLocation, Config.ColorFilter> filters = new HashMap<>();
        // keyed on the (order-insensitive) property map so later packs override same-key entries
        HashMap<ResourceLocation, LinkedHashMap<Map<String, String>, Config.ColorEmitter>> stateEmitters = new HashMap<>();
        HashMap<ResourceLocation, LinkedHashMap<Map<String, String>, Config.ColorFilter>> stateFilters = new HashMap<>();
        HashMap<EntityType<?>, EntityLight> entityLights = new HashMap<>();
        HashMap<Item, ItemLightDefinition> itemLights = new HashMap<>();

        // collect the distinct namespaces once: iterating getResourceStack per pack that
        // declares a namespace re-parses the same full stack once per such pack
        Set<String> namespaces = new HashSet<>();
        resourceManager.listPacks().forEach(pack -> namespaces.addAll(pack.getNamespaces(PackType.CLIENT_RESOURCES)));

        for(String namespace : namespaces) {
            loadFile(resourceManager, namespace, "light/emitters.json", "light emitter", (key, value) -> {
                Config.ColorEmitter emitter = Config.ColorEmitter.fromJsonElement(value);
                if(key.properties().isEmpty())
                    emitters.put(key.block(), emitter);
                else
                    stateEmitters.computeIfAbsent(key.block(), unused -> new LinkedHashMap<>()).put(key.properties(), emitter);
            });

            loadFile(resourceManager, namespace, "light/filters.json", "light color filter", (key, value) -> {
                Config.ColorFilter filter = Config.ColorFilter.fromJsonElement(value);
                if(key.properties().isEmpty())
                    filters.put(key.block(), filter);
                else
                    stateFilters.computeIfAbsent(key.block(), unused -> new LinkedHashMap<>()).put(key.properties(), filter);
            });

            loadEntityFile(resourceManager, namespace, entityLights);
            loadItemFile(resourceManager, namespace, itemLights);
        }

        Config.setColorEmitters(emitters);
        Config.setColorFilters(filters);
        Config.setStateColorEmitters(sortBySpecificity(stateEmitters, Config.StateColorEmitter::new));
        Config.setStateColorFilters(sortBySpecificity(stateFilters, Config.StateColorFilter::new));
        EntityLightManager.setJsonLights(Map.copyOf(entityLights));
        EntityLightManager.setJsonItemLights(Map.copyOf(itemLights));
        LOGGER.info("Loaded {} light emitter entries ({} state-specific), {} filter entries ({} state-specific), {} entity and {} item light entries",
                emitters.size() + countEntries(stateEmitters), countEntries(stateEmitters),
                filters.size() + countEntries(stateFilters), countEntries(stateFilters),
                entityLights.size(), itemLights.size());

        // the initial startup reload runs before FMLLoadComplete assigns the accessor
        if(ColorfulLighting.clientAccessor != null && ColorfulLighting.clientAccessor.getLevel() != null)
            ColoredLightEngine.getInstance().reset();
    }

    private void loadFile(ResourceManager resourceManager, String namespace, String path, String what, BiConsumer<EntryKey, JsonElement> sink) {
        for(Resource resource : resourceManager.getResourceStack(ResourceLocation.fromNamespaceAndPath(namespace, path))) {
            try (BufferedReader reader = resource.openAsReader()) {
                JsonObject object = GSON.fromJson(reader, JsonObject.class);
                for(var entry : object.entrySet()) {
                    try {
                        warnIfDeprecatedArrayForm(entry.getValue(), what, entry.getKey(), resource.sourcePackId());
                        sink.accept(parseEntryKey(entry.getKey()), entry.getValue());
                    }
                    catch (Exception e) {
                        warnEntryFailed(what, entry.toString(), resource.sourcePackId(), e);
                    }
                }
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load {}s from pack {}", what, resource.sourcePackId(), e);
            }
        }
    }

    /**
     * Validation failures carry an explanatory message and need no stack trace; only
     * unexpected exceptions keep theirs.
     */
    private static void warnEntryFailed(String what, String entry, String packId, Exception e) {
        if(e instanceof IllegalArgumentException) {
            LOGGER.warn("Failed to load {} entry {} from pack {}: {}", what, entry, packId, e.getMessage());
        }
        else {
            LOGGER.warn("Failed to load {} entry {} from pack {}", what, entry, packId, e);
        }
    }

    /**
     * The array forms ([r,g,b] 0-255 ints or 0.0-1.0 floats, optional 4th brightness
     * element) still parse, but the canonical external format is the hex string; steer
     * pack authors there so a future format change stays a one-parser edit.
     */
    private static void warnIfDeprecatedArrayForm(JsonElement value, String what, String entryName, String packId) {
        if(value != null && value.isJsonArray()) {
            LOGGER.warn(
                    "{} entry {} from pack {} uses the deprecated array color form;"
                            + " prefer \"#rrggbb\" or \"#rrggbb;L\" (L = hex level digit)",
                    what, entryName, packId
            );
        }
    }

    /**
     * Loads "light/entity_emitters.json": entity type id -> color value (same syntax as
     * emitter values; a missing ";X" brightness suffix means full level 15).
     */
    private void loadEntityFile(ResourceManager resourceManager, String namespace, HashMap<EntityType<?>, EntityLight> sink) {
        for(Resource resource : resourceManager.getResourceStack(ResourceLocation.fromNamespaceAndPath(namespace, "light/entity_emitters.json"))) {
            try (BufferedReader reader = resource.openAsReader()) {
                JsonObject object = GSON.fromJson(reader, JsonObject.class);
                for(var entry : object.entrySet()) {
                    try {
                        ResourceLocation key = ResourceLocation.parse(entry.getKey());
                        if(!BuiltInRegistries.ENTITY_TYPE.containsKey(key)) throw new IllegalArgumentException("Couldn't find entity type "+key);
                        warnIfDeprecatedArrayForm(entry.getValue(), "entity light", entry.getKey(), resource.sourcePackId());
                        Config.ColorEmitter emitter = Config.ColorEmitter.fromJsonElement(entry.getValue());
                        ColorRGB4 color = emitter.color();
                        int brightness = emitter.overriddenBrightness4() < 0 ? 15 : emitter.overriddenBrightness4();
                        sink.put(BuiltInRegistries.ENTITY_TYPE.get(key), EntityLight.of(color.red4, color.green4, color.blue4, brightness));
                    }
                    catch (Exception e) {
                        warnEntryFailed("entity light", entry.toString(), resource.sourcePackId(), e);
                    }
                }
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load entity lights from pack {}", resource.sourcePackId(), e);
            }
        }
    }

    /**
     * Loads "light/item_emitters.json". A plain emitter value defines a fixed item
     * light. An object may contain "light" plus "water_sensitive"; omitting "light"
     * keeps automatic BlockItem-derived color/brightness and only supplies the flag.
     */
    private void loadItemFile(
            ResourceManager resourceManager,
            String namespace,
            HashMap<Item, ItemLightDefinition> sink
    ) {
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(namespace, "light/item_emitters.json");
        for(Resource resource : resourceManager.getResourceStack(file)) {
            try (BufferedReader reader = resource.openAsReader()) {
                JsonObject object = GSON.fromJson(reader, JsonObject.class);
                for(var entry : object.entrySet()) {
                    try {
                        ResourceLocation key = ResourceLocation.parse(entry.getKey());
                        if(!BuiltInRegistries.ITEM.containsKey(key))
                            throw new IllegalArgumentException("Couldn't find item "+key);

                        JsonElement lightElement = entry.getValue();
                        boolean waterSensitive = false;
                        if(lightElement.isJsonObject()) {
                            JsonObject definition = lightElement.getAsJsonObject();
                            waterSensitive = definition.has("water_sensitive")
                                    && definition.get("water_sensitive").getAsBoolean();
                            lightElement = definition.get("light");
                        }

                        EntityLight light = null;
                        if(lightElement != null && !lightElement.isJsonNull()) {
                            warnIfDeprecatedArrayForm(lightElement, "item light", entry.getKey(), resource.sourcePackId());
                            Config.ColorEmitter emitter = Config.ColorEmitter.fromJsonElement(lightElement);
                            ColorRGB4 color = emitter.color();
                            int brightness = emitter.overriddenBrightness4() < 0
                                    ? 15
                                    : emitter.overriddenBrightness4();
                            light = EntityLight.of(color.red4, color.green4, color.blue4, brightness);
                        }
                        sink.put(
                                BuiltInRegistries.ITEM.get(key),
                                new ItemLightDefinition(light, waterSensitive)
                        );
                    }
                    catch (Exception e) {
                        warnEntryFailed("item light", entry.toString(), resource.sourcePackId(), e);
                    }
                }
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load item lights from pack {}", resource.sourcePackId(), e);
            }
        }
    }

    /**
     * Parses "modid:block" or "modid:block[prop=value,prop2=value2]". Properties are
     * validated against the block's state definition so typos are caught at load time.
     */
    private static EntryKey parseEntryKey(String rawKey) {
        int bracket = rawKey.indexOf('[');
        if(bracket < 0) {
            ResourceLocation key = ResourceLocation.parse(rawKey);
            if(!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("Couldn't find block "+key);
            return new EntryKey(key, Map.of());
        }

        if(!rawKey.endsWith("]")) throw new IllegalArgumentException("Missing closing ']' in "+rawKey);
        ResourceLocation key = ResourceLocation.parse(rawKey.substring(0, bracket).trim());
        if(!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("Couldn't find block "+key);
        Block block = BuiltInRegistries.BLOCK.get(key);

        Map<String, String> properties = new LinkedHashMap<>();
        String spec = rawKey.substring(bracket + 1, rawKey.length() - 1);
        for(String pair : spec.split(",")) {
            String[] parts = pair.split("=", 2);
            if(parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
                throw new IllegalArgumentException("Invalid property pair '"+pair+"' in "+rawKey);
            String name = parts[0].trim();
            String value = parts[1].trim();
            Property<?> property = block.getStateDefinition().getProperty(name);
            if(property == null)
                throw new IllegalArgumentException("Block "+key+" has no property '"+name+"'");
            if(property.getValue(value).isEmpty())
                throw new IllegalArgumentException("Invalid value '"+value+"' for property '"+name+"' of "+key);
            properties.put(name, value);
        }
        if(properties.isEmpty()) throw new IllegalArgumentException("Empty property list in "+rawKey);
        return new EntryKey(key, properties);
    }

    private interface StateEntryFactory<E, S> {
        S create(Map<String, String> properties, E entry);
    }

    /** Flattens collected per-block state entries into lists sorted most-specific-first. */
    private static <E, S> HashMap<ResourceLocation, List<S>> sortBySpecificity(
            HashMap<ResourceLocation, LinkedHashMap<Map<String, String>, E>> collected,
            StateEntryFactory<E, S> factory
    ) {
        HashMap<ResourceLocation, List<S>> result = new HashMap<>();
        for(var blockEntry : collected.entrySet()) {
            List<Map.Entry<Map<String, String>, E>> entries = new ArrayList<>(blockEntry.getValue().entrySet());
            entries.sort(Comparator.comparingInt((Map.Entry<Map<String, String>, E> e) -> e.getKey().size()).reversed());
            List<S> list = new ArrayList<>(entries.size());
            for(var e : entries)
                list.add(factory.create(e.getKey(), e.getValue()));
            result.put(blockEntry.getKey(), list);
        }
        return result;
    }

    private static int countEntries(HashMap<ResourceLocation, ? extends LinkedHashMap<?, ?>> collected) {
        int count = 0;
        for(var value : collected.values()) count += value.size();
        return count;
    }
}
