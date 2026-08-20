package me.erykczy.colorfullighting.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.accessors.BlockStateWrapper;
import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.EntityLightManager;
import me.erykczy.colorfullighting.common.EntityLightTextCodec;
import me.erykczy.colorfullighting.common.ItemLightDefinition;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Version 2 client-side light overrides for blocks, items, and entities. */
public final class LightUserConfig {
    public static final int VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "lights.json";
    private static final String LEGACY_FILE_NAME = "entity_lights.json";
    private static final String EXPORT_PACK_NAME = "colorful-lighting-entity-lights";

    private static volatile Snapshot current = Snapshot.empty();

    private LightUserConfig() {
    }

    public record Snapshot(
            Map<ResourceLocation, LightOverride> blocks,
            Map<ResourceLocation, LightOverride> items,
            Map<ResourceLocation, LightOverride> entities,
            @Nullable LightOverride burningEntity
    ) {
        public Snapshot {
            blocks = normalizedCopy(blocks);
            items = normalizedCopy(items);
            entities = normalizedCopy(entities);
            if(burningEntity != null && burningEntity.isEmpty()) burningEntity = null;
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), null);
        }
    }

    public static Snapshot snapshot() {
        return current;
    }

    public static Path configPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(ColorfulLighting.MOD_ID)
                .resolve(FILE_NAME);
    }

    public static void loadAndApply() {
        Path path = configPath();
        Snapshot loaded = Snapshot.empty();
        boolean writeFreshConfig = !Files.isRegularFile(path);

        if(Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                loaded = decode(JsonParser.parseReader(reader));
                ColorfulLighting.LOGGER.info(
                        "Loaded light overrides: {} blocks, {} items, {} entities",
                        loaded.blocks().size(), loaded.items().size(), loaded.entities().size()
                );
            }
            catch (Exception exception) {
                ColorfulLighting.LOGGER.error(
                        "Could not read {}; backing it up and rebuilding an empty v2 config",
                        path,
                        exception
                );
                backupOnce(path, ".invalid.bak");
                writeFreshConfig = true;
            }
        }
        else {
            Path legacy = path.resolveSibling(LEGACY_FILE_NAME);
            if(Files.isRegularFile(legacy)) {
                backupOnce(legacy, ".legacy.bak");
                ColorfulLighting.LOGGER.info(
                        "Found legacy {}; kept a backup and started a clean v2 config",
                        legacy
                );
            }
            Snapshot migrated = migrateLegacyClientToml();
            if(migrated != null) loaded = migrated;
        }

        current = loaded;
        applyToRuntime(loaded);
        if(writeFreshConfig) {
            try {
                writeAtomically(path, GSON.toJson(encodeConfig(loaded)));
            }
            catch (IOException exception) {
                ColorfulLighting.LOGGER.error("Could not create fresh light config {}", path, exception);
            }
        }
    }

    public static void saveAndApply(Snapshot snapshot) throws IOException {
        Snapshot normalized = new Snapshot(
                snapshot.blocks(), snapshot.items(), snapshot.entities(), snapshot.burningEntity()
        );
        writeAtomically(configPath(), GSON.toJson(encodeConfig(normalized)));
        current = normalized;
        applyToRuntime(normalized);
        EntityLightManager.reset();
        if(ColorfulLighting.clientAccessor != null && ColorfulLighting.clientAccessor.getLevel() != null)
            ColoredLightEngine.getInstance().reset();
    }

    /** Exports the editable layer as the three client resource-pack emitter files. */
    public static Path exportResourcePack(Snapshot snapshot) throws IOException {
        Snapshot normalized = new Snapshot(
                snapshot.blocks(), snapshot.items(), snapshot.entities(), snapshot.burningEntity()
        );
        Path packRoot = FMLPaths.GAMEDIR.get().resolve("resourcepacks").resolve(EXPORT_PACK_NAME);
        Path lightDirectory = packRoot
                .resolve("assets")
                .resolve(ColorfulLighting.MOD_ID)
                .resolve("light");
        Files.createDirectories(lightDirectory);

        JsonObject pack = new JsonObject();
        JsonObject metadata = new JsonObject();
        metadata.addProperty(
                "pack_format",
                SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES)
        );
        metadata.addProperty(
                "description",
                "Light overrides exported by Colorful Lighting"
        );
        pack.add("pack", metadata);
        writeAtomically(packRoot.resolve("pack.mcmeta"), GSON.toJson(pack));

        JsonObject blockEntries = new JsonObject();
        sorted(normalized.blocks()).forEach((id, override) ->
                appendBlockExport(blockEntries, id, override)
        );
        writeAtomically(lightDirectory.resolve("emitters.json"), GSON.toJson(blockEntries));

        JsonObject itemEntries = new JsonObject();
        sorted(normalized.items()).forEach((id, override) -> {
            JsonElement exported = encodeItemExport(id, override, normalized);
            if(exported != null) itemEntries.add(id.toString(), exported);
        });
        writeAtomically(lightDirectory.resolve("item_emitters.json"), GSON.toJson(itemEntries));

        JsonObject entityEntries = new JsonObject();
        sorted(normalized.entities()).forEach((id, override) -> {
            EntityLight base = null;
            if(BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
                base = EntityLightManager.defaultLightForEditor(type);
            }
            EntityLight resolved = override.apply(base);
            if(override.disabled())
                entityEntries.addProperty(id.toString(), "#000000;0");
            else if(resolved != null)
                entityEntries.addProperty(id.toString(), encodeLight(resolved));
        });
        writeAtomically(lightDirectory.resolve("entity_emitters.json"), GSON.toJson(entityEntries));
        return packRoot;
    }

    public static JsonObject encodeConfig(Snapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("blocks", encodeSection(snapshot.blocks()));
        root.add("items", encodeSection(snapshot.items()));
        root.add("entities", encodeSection(snapshot.entities()));
        if(snapshot.burningEntity() != null)
            root.add("burning_entity", encodeOverride(snapshot.burningEntity()));
        return root;
    }

    public static Snapshot decode(JsonElement document) {
        if(document == null || !document.isJsonObject())
            throw new IllegalArgumentException("Light config root must be an object");
        JsonObject root = document.getAsJsonObject();
        if(!root.has("version") || root.get("version").getAsInt() != VERSION)
            throw new IllegalArgumentException("Unsupported light config version");

        LightOverride burning = null;
        if(root.has("burning_entity") && !root.get("burning_entity").isJsonNull())
            burning = decodeOverride(root.get("burning_entity"));
        return new Snapshot(
                decodeSection(root, "blocks"),
                decodeSection(root, "items"),
                decodeSection(root, "entities"),
                burning
        );
    }

    public static JsonObject encodeOverride(LightOverride override) {
        JsonObject value = new JsonObject();
        if(override.disabled()) {
            value.addProperty("enabled", false);
            return value;
        }
        if(override.color() != null) value.addProperty("color", encodeColor(override.color()));
        if(override.brightness() != null) value.addProperty("brightness", override.brightness());
        return value;
    }

    public static LightOverride decodeOverride(JsonElement element) {
        if(!element.isJsonObject())
            throw new IllegalArgumentException("Light override must be an object");
        JsonObject value = element.getAsJsonObject();
        if(value.has("enabled") && !value.get("enabled").getAsBoolean())
            return LightOverride.disabledOverride();
        ColorRGB4 color = value.has("color") ? decodeColor(value.get("color").getAsString()) : null;
        Integer brightness = value.has("brightness") ? value.get("brightness").getAsInt() : null;
        return new LightOverride(color, brightness, false);
    }

    /**
     * 2.3.x registered a NeoForge client config (colorful_lighting-client.toml) with
     * five entity light levels. 2.4 no longer registers it, so on the first run
     * without a lights.json, values a user changed from their 2.3.x defaults are
     * imported as v2 overrides (level 0 becomes a disabled override). The toml itself
     * is backed up and left in place.
     */
    @Nullable
    private static Snapshot migrateLegacyClientToml() {
        Path toml = FMLPaths.CONFIGDIR.get().resolve(ColorfulLighting.MOD_ID + "-client.toml");
        if(!Files.isRegularFile(toml)) return null;
        try (com.electronwill.nightconfig.core.file.FileConfig config =
                com.electronwill.nightconfig.core.file.FileConfig.of(toml)) {
            config.load();
            HashMap<ResourceLocation, LightOverride> entities = new HashMap<>();
            putLegacyEntityOverride(entities, config, "glowSquidLightLevel", "minecraft:glow_squid", 7);
            putLegacyEntityOverride(entities, config, "blazeLightLevel", "minecraft:blaze", 10);
            putLegacyEntityOverride(entities, config, "magmaCubeLightLevel", "minecraft:magma_cube", 8);
            putLegacyEntityOverride(entities, config, "allayLightLevel", "minecraft:allay", 5);
            LightOverride burning = legacyLevelOverride(
                    config.get("entity_lights.burningEntityLightLevel"), 15
            );
            if(burning == null && entities.isEmpty()) return null;

            backupOnce(toml, ".migrated.bak");
            ColorfulLighting.LOGGER.info(
                    "Migrated {} entity light level(s) from legacy {} into the v2 light config",
                    entities.size() + (burning == null ? 0 : 1), toml.getFileName()
            );
            return new Snapshot(Map.of(), Map.of(), entities, burning);
        }
        catch (Exception exception) {
            ColorfulLighting.LOGGER.warn("Could not migrate legacy client config {}", toml, exception);
            return null;
        }
    }

    private static void putLegacyEntityOverride(
            HashMap<ResourceLocation, LightOverride> entities,
            com.electronwill.nightconfig.core.file.FileConfig config,
            String key,
            String entityId,
            int defaultLevel
    ) {
        LightOverride override = legacyLevelOverride(config.get("entity_lights." + key), defaultLevel);
        if(override != null) entities.put(ResourceLocation.parse(entityId), override);
    }

    /** Only levels the user changed from their 2.3.x defaults become overrides. */
    @Nullable
    private static LightOverride legacyLevelOverride(Object raw, int defaultLevel) {
        if(!(raw instanceof Number number)) return null;
        int level = Math.clamp(number.intValue(), 0, 15);
        if(level == defaultLevel) return null;
        return level == 0 ? LightOverride.disabledOverride() : new LightOverride(null, level, false);
    }

    // hex parsing/printing delegates to the canonical codec (EntityLightTextCodec)

    public static String encodeColor(ColorRGB4 color) {
        return EntityLightTextCodec.encodeRgb8(color.red4 * 17, color.green4 * 17, color.blue4 * 17);
    }

    public static ColorRGB4 decodeColor(String text) {
        int rgb = EntityLightTextCodec.parseRgb8(text);
        return ColorRGB4.fromRGB8((rgb >>> 16) & 0xff, (rgb >>> 8) & 0xff, rgb & 0xff);
    }

    private static JsonObject encodeSection(Map<ResourceLocation, LightOverride> section) {
        JsonObject result = new JsonObject();
        sorted(section).forEach((id, override) -> result.add(id.toString(), encodeOverride(override)));
        return result;
    }

    private static Map<ResourceLocation, LightOverride> decodeSection(JsonObject root, String name) {
        if(!root.has(name)) return Map.of();
        if(!root.get(name).isJsonObject())
            throw new IllegalArgumentException("Section '" + name + "' must be an object");
        HashMap<ResourceLocation, LightOverride> result = new HashMap<>();
        for(var entry : root.getAsJsonObject(name).entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if(id == null) {
                ColorfulLighting.LOGGER.warn("Ignoring invalid {} light id '{}'", name, entry.getKey());
                continue;
            }
            try {
                LightOverride override = decodeOverride(entry.getValue());
                if(!override.isEmpty()) result.put(id, override);
            }
            catch (RuntimeException exception) {
                ColorfulLighting.LOGGER.warn("Ignoring invalid {} light override for {}", name, id, exception);
            }
        }
        return Map.copyOf(result);
    }

    private static void appendBlockExport(
            JsonObject sink,
            ResourceLocation id,
            LightOverride override
    ) {
        Config.ColorEmitter plainBase = Config.defaultPlainEmitterForEditor(id);
        ColorRGB4 plainColor = override.color() != null
                ? override.color()
                : plainBase == null ? Config.defaultColor : plainBase.color();
        Integer plainBrightness = override.brightness() != null
                ? override.brightness()
                : plainBase != null && plainBase.overriddenBrightness4() >= 0
                        ? plainBase.overriddenBrightness4()
                        : null;
        sink.addProperty(
                id.toString(),
                override.disabled()
                        ? "#000000;0"
                        : encodeEmitter(plainColor, plainBrightness)
        );

        for(Config.StateColorEmitter state : Config.defaultStateEmittersForEditor(id)) {
            ColorRGB4 color = override.color() != null ? override.color() : state.emitter().color();
            Integer brightness = override.brightness() != null
                    ? override.brightness()
                    : state.emitter().overriddenBrightness4() >= 0
                            ? state.emitter().overriddenBrightness4()
                            : null;
            sink.addProperty(
                    encodeStateKey(id, state.properties()),
                    override.disabled()
                            ? "#000000;0"
                            : encodeEmitter(color, brightness)
            );
        }
    }

    private static String encodeStateKey(ResourceLocation id, Map<String, String> properties) {
        StringBuilder result = new StringBuilder(id.toString()).append('[');
        properties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if(result.charAt(result.length() - 1) != '[') result.append(',');
                    result.append(entry.getKey()).append('=').append(entry.getValue());
                });
        return result.append(']').toString();
    }

    private static String encodeEmitter(ColorRGB4 color, @Nullable Integer brightness) {
        String encoded = encodeColor(color);
        return brightness == null
                ? encoded
                : encoded + ";" + Integer.toHexString(brightness).toUpperCase();
    }

    @Nullable
    private static JsonElement encodeItemExport(
            ResourceLocation id,
            LightOverride override,
            Snapshot snapshot
    ) {
        if(override.disabled()) return GSON.toJsonTree("#000000;0");
        if(!BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        ItemLightDefinition definition = EntityLightManager.defaultItemDefinitionsForEditor().get(id);
        EntityLight base = definition == null ? null : definition.light();
        if(base == null && item instanceof BlockItem blockItem)
            base = blockLightForExport(blockItem.getBlock(), snapshot.blocks());
        EntityLight resolved = override.apply(base);
        if(resolved == null) return null;

        String lightText = encodeLight(resolved);
        if(definition != null && definition.waterSensitive()) {
            JsonObject object = new JsonObject();
            object.addProperty("light", lightText);
            object.addProperty("water_sensitive", true);
            return object;
        }
        return GSON.toJsonTree(lightText);
    }

    @Nullable
    private static EntityLight blockLightForExport(
            Block block,
            Map<ResourceLocation, LightOverride> blockOverrides
    ) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        BlockStateWrapper state = new BlockStateWrapper(block.defaultBlockState());
        Config.ColorEmitter emitter = Config.defaultEmitterForEditor(state);
        ColorRGB4 color = emitter == null ? Config.defaultColor : emitter.color();
        int brightness = emitter != null && emitter.overriddenBrightness4() >= 0
                ? emitter.overriddenBrightness4()
                : state.getLightEmission();
        EntityLight base = brightness == 0
                ? null
                : EntityLight.of(color.red4, color.green4, color.blue4, brightness);
        LightOverride override = blockOverrides.get(id);
        return override == null ? base : override.apply(base);
    }

    private static String encodeLight(EntityLight light) {
        return encodeColor(ColorRGB4.fromRGB4(light.red4(), light.green4(), light.blue4()))
                + ";" + Integer.toHexString(light.brightness4()).toUpperCase();
    }

    private static Map<ResourceLocation, LightOverride> normalizedCopy(
            Map<ResourceLocation, LightOverride> source
    ) {
        HashMap<ResourceLocation, LightOverride> result = new HashMap<>();
        source.forEach((id, override) -> {
            if(id != null && override != null && !override.isEmpty()) result.put(id, override);
        });
        return Map.copyOf(result);
    }

    private static TreeMap<ResourceLocation, LightOverride> sorted(
            Map<ResourceLocation, LightOverride> source
    ) {
        TreeMap<ResourceLocation, LightOverride> result = new TreeMap<>(
                (first, second) -> first.toString().compareTo(second.toString())
        );
        result.putAll(source);
        return result;
    }

    private static void applyToRuntime(Snapshot snapshot) {
        Config.setUserBlockOverrides(snapshot.blocks());

        HashMap<Item, LightOverride> items = new HashMap<>();
        snapshot.items().forEach((id, override) -> {
            if(BuiltInRegistries.ITEM.containsKey(id))
                items.put(BuiltInRegistries.ITEM.get(id), override);
        });
        HashMap<EntityType<?>, LightOverride> entities = new HashMap<>();
        snapshot.entities().forEach((id, override) -> {
            if(BuiltInRegistries.ENTITY_TYPE.containsKey(id))
                entities.put(BuiltInRegistries.ENTITY_TYPE.get(id), override);
        });
        EntityLightManager.setUserOverrides(items, entities, snapshot.burningEntity());
    }

    private static void backupOnce(Path source, String suffix) {
        if(!Files.isRegularFile(source)) return;
        // never skip because an older backup exists: a second corruption event would
        // otherwise overwrite the user's current file with a fresh empty config while
        // preserving only the stale first backup
        Path backup = source.resolveSibling(source.getFileName() + suffix);
        for(int attempt = 2; Files.exists(backup) && attempt <= 20; attempt++) {
            backup = source.resolveSibling(source.getFileName() + suffix.replace(".bak", "-" + attempt + ".bak"));
        }
        try {
            Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException exception) {
            ColorfulLighting.LOGGER.error("Could not back up {} to {}", source, backup, exception);
        }
    }

    private static void writeAtomically(Path target, String contents) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
