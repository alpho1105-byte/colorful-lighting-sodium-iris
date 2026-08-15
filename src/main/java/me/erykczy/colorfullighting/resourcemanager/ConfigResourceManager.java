package me.erykczy.colorfullighting.resourcemanager;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
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
        }

        Config.setColorEmitters(emitters);
        Config.setColorFilters(filters);
        Config.setStateColorEmitters(sortBySpecificity(stateEmitters, Config.StateColorEmitter::new));
        Config.setStateColorFilters(sortBySpecificity(stateFilters, Config.StateColorFilter::new));
        LOGGER.info("Loaded {} light emitter entries ({} state-specific) and {} filter entries ({} state-specific)",
                emitters.size() + countEntries(stateEmitters), countEntries(stateEmitters),
                filters.size() + countEntries(stateFilters), countEntries(stateFilters));

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
                        sink.accept(parseEntryKey(entry.getKey()), entry.getValue());
                    }
                    catch (Exception e) {
                        LOGGER.warn("Failed to load {} entry {} from pack {}", what, entry.toString(), resource.sourcePackId(), e);
                    }
                }
            }
            catch (Exception e) {
                LOGGER.warn("Failed to load {}s from pack {}", what, resource.sourcePackId(), e);
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
