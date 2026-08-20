package me.erykczy.colorfullighting.common;

import dev.colorfullighting.compat.ColorfulLightGate;
import dev.colorfullighting.compat.create.CreateContraptionLightCompat;
import me.erykczy.colorfullighting.accessors.BlockStateWrapper;
import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.api.EntityLightProvider;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.config.LightOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side colored light sources attached to entities, held items, dropped items,
 * displays, projectiles, and optional Create contraptions.
 *
 * <p>Without a shader pack, moving sources are sampled directly from their continuous
 * world positions. This avoids the remove/re-propagate cycle (and visible dark frame)
 * that occurs when an entity crosses a block boundary. Supported Iris packs instead
 * receive smooth point-light uniforms; the CPU source list is empty in that mode so a
 * held or entity light cannot be rendered twice.
 */
public final class EntityLightManager {
    /** Keep in sync with IrisShaderCompat.MAX_DYNAMIC_LIGHTS. */
    private static final int MAX_SHADER_LIGHTS = 8;
    /** Same movement threshold used by LambDynamicLights before remeshing sections. */
    private static final double REMESH_MOVEMENT_THRESHOLD = 0.1;
    /** Built-in brightness used by the special burning-entity rule. */
    private static final int DEFAULT_BURNING_ENTITY_BRIGHTNESS = 15;

    private static final ConcurrentHashMap<EntityType<?>, EntityLightProvider> providers =
            new ConcurrentHashMap<>();
    private static volatile Map<EntityType<?>, EntityLight> jsonLights = Map.of();
    private static volatile Map<Item, ItemLightDefinition> jsonItemLights = Map.of();
    private static volatile Map<EntityType<?>, LightOverride> userEntityOverrides = Map.of();
    private static volatile Map<Item, LightOverride> userItemOverrides = Map.of();
    @Nullable
    private static volatile LightOverride burningUserOverride;

    private record SourceKey(Entity entity, int index) {
    }

    // Component indices for SourceKey: an entity's light registers as up to three
    // independent sources, so a dim hue never inherits a brighter component's falloff
    // range (DynamicLightMath.max would fold them into one tint whose every channel
    // rides the peak component's radius). Create contraption lights use indices >= 0.
    private static final int COMPONENT_BASE = -1;
    private static final int COMPONENT_CARRIED = -2;
    private static final int COMPONENT_BURNING = -3;

    private record EngineLight(Vec3 position, EntityLight light) {
    }

    /**
     * Immutable 16x16x16 spatial buckets, published once per client tick. A source has
     * at most range 15, so a sample only needs its own section and 26 neighbours.
     */
    private static volatile Map<Long, List<EngineLight>> engineLightsBySection = Map.of();
    /** Positions/colors represented by the last section rebuild request. Main thread only. */
    private static final HashMap<SourceKey, EngineLight> lastRemeshedLights = new HashMap<>();

    private record ShaderLight(
            Entity entity,
            @Nullable Vec3 fixedPosition,
            EntityLight light
    ) {
    }

    private static volatile List<ShaderLight> shaderLights = List.of();

    // Per-frame uniform data, rebuilt at most once per frame window (render thread).
    private static long lastFrameNanos;
    private static int frameCount;
    private static final float[] framePositions = new float[MAX_SHADER_LIGHTS * 4];
    private static final float[] frameColors = new float[MAX_SHADER_LIGHTS * 4];
    // Reused per-slot vectors: Iris copies a uniform supplier's value immediately on
    // update, so handing back a mutable cached instance is safe and avoids ~32
    // allocations per frame across the per-slot uniforms.
    private static final org.joml.Vector4f[] framePositionVectors = new org.joml.Vector4f[MAX_SHADER_LIGHTS];
    private static final org.joml.Vector4f[] frameColorVectors = new org.joml.Vector4f[MAX_SHADER_LIGHTS];
    static {
        for(int i = 0; i < MAX_SHADER_LIGHTS; i++) {
            framePositionVectors[i] = new org.joml.Vector4f();
            frameColorVectors[i] = new org.joml.Vector4f();
        }
    }

    private EntityLightManager() {
    }

    public static void registerProvider(EntityType<?> type, EntityLightProvider provider) {
        providers.put(type, provider);
    }

    public static void setJsonLights(Map<EntityType<?>, EntityLight> lights) {
        jsonLights = lights;
    }

    public static void setJsonItemLights(Map<Item, ItemLightDefinition> lights) {
        jsonItemLights = lights;
    }

    public static void setUserOverrides(
            Map<Item, LightOverride> itemOverrides,
            Map<EntityType<?>, LightOverride> entityOverrides,
            @Nullable LightOverride burningOverride
    ) {
        userItemOverrides = Map.copyOf(itemOverrides);
        userEntityOverrides = Map.copyOf(entityOverrides);
        burningUserOverride = burningOverride;
    }

    /** Resource-pack/default entries shown by the editor before user overrides. */
    public static Map<ResourceLocation, EntityLight> defaultLightsForEditor() {
        HashMap<ResourceLocation, EntityLight> result = new HashMap<>();
        jsonLights.forEach((type, light) -> {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if(id != null) result.put(id, light);
        });
        return Map.copyOf(result);
    }

    public static Set<ResourceLocation> providerIdsForEditor() {
        java.util.HashSet<ResourceLocation> result = new java.util.HashSet<>();
        providers.keySet().forEach(type -> {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if(id != null) result.add(id);
        });
        return Set.copyOf(result);
    }

    @Nullable
    public static EntityLight defaultLightForEditor(EntityType<?> type) {
        return jsonLights.get(type);
    }

    public static Map<ResourceLocation, ItemLightDefinition> defaultItemDefinitionsForEditor() {
        HashMap<ResourceLocation, ItemLightDefinition> result = new HashMap<>();
        jsonItemLights.forEach((item, definition) -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if(id != null) result.put(id, definition);
        });
        return Map.copyOf(result);
    }

    public static EntityLight defaultBurningLightForEditor() {
        ColorRGB4 color = Config.getLightColor(Blocks.FIRE.builtInRegistryHolder().getKey());
        return EntityLight.of(
                color.red4,
                color.green4,
                color.blue4,
                DEFAULT_BURNING_ENTITY_BRIGHTNESS
        );
    }

    public static EntityLight burningLightForEditor() {
        EntityLight base = defaultBurningLightForEditor();
        if(burningUserOverride == null) return base;
        EntityLight configured = burningUserOverride.apply(base);
        return configured == null ? EntityLight.of(0, 0, 0, 0) : configured;
    }

    public static void tick(ColoredLightEngine engine) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if(level == null) {
            shaderLights = List.of();
            engineLightBounds = null;
            engineLightsBySection = Map.of();
            lastRemeshedLights.clear();
            return;
        }

        boolean shaderMode = ColorfulLightGate.shaderEntityLightsActive();
        List<ShaderLight> smoothLights = shaderMode ? new ArrayList<>() : null;
        HashMap<SourceKey, EngineLight> computed = new HashMap<>();

        // ClientLevel's renderable-entity iterable is renderer-owned and does not
        // consistently include the first-person camera player. Sample it explicitly;
        // this is the authoritative path for the local player's held light.
        if(minecraft.player != null) {
            addEntityComponents(computed, smoothLights, minecraft, minecraft.player);
        }

        for(Entity entity : level.entitiesForRendering()) {
            if(entity == minecraft.player) continue;
            addEntityComponents(computed, smoothLights, minecraft, entity);

            int[] contraptionLightIndex = {0};
            CreateContraptionLightCompat.collect(entity, level.getGameTime(),
                    (position, light) -> addSource(
                            computed,
                            smoothLights,
                            minecraft,
                            new SourceKey(entity, contraptionLightIndex[0]++),
                            entity,
                            position,
                            light
                    ));
        }
        shaderLights = smoothLights == null ? List.of() : List.copyOf(smoothLights);
        publishEngineLights(computed);
        requestNeededRemeshes(computed, engine);
    }

    /** Registers each of an entity's light components as an independent source. */
    private static void addEntityComponents(
            Map<SourceKey, EngineLight> computed,
            @Nullable List<ShaderLight> smoothLights,
            Minecraft minecraft,
            Entity entity
    ) {
        // one visibility gate for every component
        if(entity.isInvisible() || entity.isSpectator()) return;

        EntityLight base = resolveBaseLight(entity);
        if(base != null)
            addSource(computed, smoothLights, minecraft,
                    new SourceKey(entity, COMPONENT_BASE), entity, null, base);

        EntityLight carried = lightFromCarriedContent(entity);
        if(carried != null && !carried.isDark())
            addSource(computed, smoothLights, minecraft,
                    new SourceKey(entity, COMPONENT_CARRIED), entity, null, carried);

        EntityLight burning = resolveBurningLight(entity);
        if(burning != null)
            addSource(computed, smoothLights, minecraft,
                    new SourceKey(entity, COMPONENT_BURNING), entity, null, burning);
    }

    private static void addSource(
            Map<SourceKey, EngineLight> computed,
            @Nullable List<ShaderLight> smoothLights,
            Minecraft minecraft,
            SourceKey key,
            Entity entity,
            @Nullable Vec3 fixedPosition,
            EntityLight light
    ) {
        Vec3 position = fixedPosition == null ? entity.getEyePosition() : fixedPosition;
        if(smoothLights == null)
            computed.put(key, new EngineLight(position, light));

        // Shader packs already have a dedicated held-light path for the local player
        // (the HeldLightColors uniforms), so only the player's CARRIED component stays
        // out of the generic point-light list; the base and burning components must
        // still render as point lights. Hands only: armor-slot light is folded into
        // the carried component and remains a pre-existing shader-mode gap, not a
        // regression of the component split.
        if(smoothLights != null
                && !(entity == minecraft.player && key.index() == COMPONENT_CARRIED))
            smoothLights.add(new ShaderLight(entity, fixedPosition, light));
    }

    /** World-space bounds of all engine lights, expanded by the maximum range (15). */
    private record EngineLightBounds(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ
    ) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    @Nullable
    private static volatile EngineLightBounds engineLightBounds;

    private static void publishEngineLights(Map<SourceKey, EngineLight> lights) {
        if(lights.isEmpty()) {
            engineLightBounds = null;
            engineLightsBySection = Map.of();
            return;
        }

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        HashMap<Long, List<EngineLight>> mutableBuckets = new HashMap<>();
        for(EngineLight light : lights.values()) {
            Vec3 position = light.position();
            minX = Math.min(minX, position.x); maxX = Math.max(maxX, position.x);
            minY = Math.min(minY, position.y); maxY = Math.max(maxY, position.y);
            minZ = Math.min(minZ, position.z); maxZ = Math.max(maxZ, position.z);
            long section = SectionPos.asLong(
                    SectionPos.blockToSectionCoord((int) Math.floor(position.x)),
                    SectionPos.blockToSectionCoord((int) Math.floor(position.y)),
                    SectionPos.blockToSectionCoord((int) Math.floor(position.z))
            );
            mutableBuckets.computeIfAbsent(section, unused -> new ArrayList<>()).add(light);
        }

        HashMap<Long, List<EngineLight>> immutableBuckets = new HashMap<>();
        mutableBuckets.forEach((section, bucket) ->
                immutableBuckets.put(section, List.copyOf(bucket))
        );
        // bounds first: samplers early-out on the bounds, so publishing the buckets
        // before them could only cause a harmless one-sample miss, not a stale hit
        engineLightBounds = new EngineLightBounds(
                minX - 15.0, minY - 15.0, minZ - 15.0,
                maxX + 15.0, maxY + 15.0, maxZ + 15.0
        );
        engineLightsBySection = Map.copyOf(immutableBuckets);
    }

    /** Samples non-shader moving lights at an exact world position. */
    public static ColorRGB4 sampleLightColor(Vec3 position) {
        return sampleLightColor(position.x, position.y, position.z);
    }

    /** Primitive overload for the per-vertex meshing path (avoids a Vec3 per vertex). */
    public static ColorRGB4 sampleLightColor(double x, double y, double z) {
        // bounds test first: with any light present, the 27-bucket scan below would
        // otherwise run for every terrain vertex in render distance
        EngineLightBounds bounds = engineLightBounds;
        if(bounds == null || !bounds.contains(x, y, z)) return ColorRGB4.fromRGB4(0, 0, 0);
        Map<Long, List<EngineLight>> buckets = engineLightsBySection;
        if(buckets.isEmpty()) return ColorRGB4.fromRGB4(0, 0, 0);

        int sectionX = SectionPos.blockToSectionCoord((int) Math.floor(x));
        int sectionY = SectionPos.blockToSectionCoord((int) Math.floor(y));
        int sectionZ = SectionPos.blockToSectionCoord((int) Math.floor(z));
        int red = 0;
        int green = 0;
        int blue = 0;
        for(int dx = -1; dx <= 1; dx++) {
            for(int dy = -1; dy <= 1; dy++) {
                for(int dz = -1; dz <= 1; dz++) {
                    List<EngineLight> lights = buckets.get(SectionPos.asLong(
                            sectionX + dx,
                            sectionY + dy,
                            sectionZ + dz
                    ));
                    if(lights == null) continue;
                    for(EngineLight light : lights) {
                        ColorRGB4 color = DynamicLightMath.effectiveColorAt(
                                light.light(),
                                Math.sqrt(light.position().distanceToSqr(x, y, z))
                        );
                        red = Math.max(red, color.red4);
                        green = Math.max(green, color.green4);
                        blue = Math.max(blue, color.blue4);
                    }
                }
            }
        }
        return ColorRGB4.fromRGB4(red, green, blue);
    }

    private static void requestNeededRemeshes(
            Map<SourceKey, EngineLight> current,
            ColoredLightEngine engine
    ) {
        lastRemeshedLights.entrySet().removeIf(entry -> {
            if(current.containsKey(entry.getKey())) return false;
            markAffectedSections(engine, entry.getValue());
            return true;
        });

        for(var entry : current.entrySet()) {
            SourceKey key = entry.getKey();
            EngineLight now = entry.getValue();
            EngineLight before = lastRemeshedLights.get(key);
            if(before == null) {
                markAffectedSections(engine, now);
                lastRemeshedLights.put(key, now);
                continue;
            }

            if(!before.light().equals(now.light()) || movedEnough(before.position(), now.position())) {
                markAffectedSections(engine, before);
                markAffectedSections(engine, now);
                lastRemeshedLights.put(key, now);
            }
        }
    }

    private static boolean movedEnough(Vec3 before, Vec3 now) {
        return Math.abs(before.x - now.x) > REMESH_MOVEMENT_THRESHOLD
                || Math.abs(before.y - now.y) > REMESH_MOVEMENT_THRESHOLD
                || Math.abs(before.z - now.z) > REMESH_MOVEMENT_THRESHOLD;
    }

    private static void markAffectedSections(ColoredLightEngine engine, EngineLight light) {
        engine.markDynamicLightDirty(light.position(), light.light().brightness4());
    }

    public static void reset() {
        engineLightBounds = null;
        engineLightsBySection = Map.of();
        lastRemeshedLights.clear();
        shaderLights = List.of();
        CreateContraptionLightCompat.reset();
    }

    // ---- per-frame uniform suppliers (render thread) ----

    public static int shaderLightCount() {
        computeFrameData();
        return frameCount;
    }

    /** Camera-relative position (xyz) and effective range (w) of shader light slot. */
    public static org.joml.Vector4f shaderLightPosition(int index) {
        computeFrameData();
        int base = index * 4;
        return framePositionVectors[index].set(
                framePositions[base], framePositions[base + 1],
                framePositions[base + 2], framePositions[base + 3]
        );
    }

    /** Color (rgb, 0..1) of shader light slot {@code index}. */
    public static org.joml.Vector4f shaderLightColor(int index) {
        computeFrameData();
        int base = index * 4;
        return frameColorVectors[index].set(
                frameColors[base], frameColors[base + 1],
                frameColors[base + 2], frameColors[base + 3]
        );
    }

    private static void computeFrameData() {
        long now = System.nanoTime();
        if(now - lastFrameNanos < 2_000_000) return;
        lastFrameNanos = now;

        frameCount = 0;
        List<ShaderLight> lights = shaderLights;
        Minecraft minecraft = Minecraft.getInstance();
        if(lights.isEmpty() || minecraft.level == null) return;

        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().getPosition();
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);

        record Positioned(Vec3 relative, EntityLight light, double distanceSq) {
        }
        List<Positioned> positioned = new ArrayList<>(lights.size());
        for(ShaderLight shaderLight : lights) {
            Entity entity = shaderLight.entity();
            if(entity.isRemoved()) continue;
            Vec3 worldPosition = shaderLight.fixedPosition() == null
                    ? entity.getEyePosition(partialTick)
                    : shaderLight.fixedPosition();
            Vec3 relative = worldPosition.subtract(cameraPos);
            positioned.add(new Positioned(relative, shaderLight.light(), relative.lengthSqr()));
        }
        positioned.sort((a, b) -> Double.compare(a.distanceSq(), b.distanceSq()));

        int count = Math.min(positioned.size(), MAX_SHADER_LIGHTS);
        for(int i = 0; i < count; i++) {
            Positioned entry = positioned.get(i);
            framePositions[i * 4] = (float) entry.relative().x;
            framePositions[i * 4 + 1] = (float) entry.relative().y;
            framePositions[i * 4 + 2] = (float) entry.relative().z;
            framePositions[i * 4 + 3] = entry.light().brightness4();
            frameColors[i * 4] = entry.light().red4() / 15.0f;
            frameColors[i * 4 + 1] = entry.light().green4() / 15.0f;
            frameColors[i * 4 + 2] = entry.light().blue4() / 15.0f;
            frameColors[i * 4 + 3] = 1.0f;
        }
        frameCount = count;
    }

    // ---- light resolution ----

    /** The entity's own (provider/JSON + user override) light, without carried or burning components. */
    @Nullable
    private static EntityLight resolveBaseLight(Entity entity) {
        EntityLight light;
        EntityType<?> type = entity.getType();
        EntityLightProvider provider = providers.get(type);
        light = provider != null ? provider.getLight(entity) : jsonLights.get(type);
        LightOverride entityOverride = userEntityOverrides.get(type);
        if(entityOverride != null)
            light = entityOverride.apply(light);
        return light == null || light.isDark() ? null : light;
    }

    @Nullable
    private static EntityLight resolveBurningLight(Entity entity) {
        if(!entity.isOnFire()) return null;
        EntityLight burningLight = burningLightForEditor();
        return burningLight.isDark() ? null : burningLight;
    }

    @Nullable
    private static EntityLight lightFromCarriedContent(Entity entity) {
        EntityLight light = null;
        if(entity instanceof LivingEntity living) {
            boolean wet = living.isUnderWater();
            for(ItemStack stack : living.getAllSlots())
                light = merge(light, getItemLight(stack, wet));
        }
        else if(entity instanceof ItemEntity itemEntity) {
            light = getItemLight(itemEntity.getItem(), itemEntity.isInWater());
        }
        else if(entity instanceof ItemFrame itemFrame) {
            light = getItemLight(itemFrame.getItem(), itemFrame.isInWater());
        }
        else if(entity instanceof Display.ItemDisplay itemDisplay) {
            Display.ItemDisplay.ItemRenderState renderState = itemDisplay.itemRenderState();
            if(renderState != null)
                light = getItemLight(renderState.itemStack(), itemDisplay.isInWater());
        }
        else if(entity instanceof Display.BlockDisplay blockDisplay) {
            Display.BlockDisplay.BlockRenderState renderState = blockDisplay.blockRenderState();
            if(renderState != null)
                light = lightFromBlockState(renderState.blockState());
        }
        else if(entity instanceof ThrowableItemProjectile projectile) {
            light = getItemLight(projectile.getItem(), projectile.isInWater());
        }
        else if(entity instanceof FallingBlockEntity fallingBlock) {
            light = lightFromBlockState(fallingBlock.getBlockState());
        }
        return light;
    }

    /** Resolves a held/dropped/displayed stack without depending on another light mod. */
    @Nullable
    public static EntityLight getItemLight(ItemStack stack, boolean wet) {
        return heldLightDecision(stack, wet).light();
    }

    /**
     * How the shader-pack held-light path should treat a stack. Authoritative when
     * Colorful Lighting has an opinion - a JSON item definition (including its
     * water-sensitive suppression), a user override (including disabled), or a
     * BlockItem-derived emission - even when that opinion is "dark". Deferred for
     * unknown items, so the pack's own item-light definitions stay in charge.
     */
    public static HeldLightDecision heldLightDecision(ItemStack stack, boolean wet) {
        if(stack.isEmpty()) return HeldLightDecision.DEFER;

        ItemLightDefinition definition = jsonItemLights.get(stack.getItem());
        LightOverride override = userItemOverrides.get(stack.getItem());
        if(override != null && override.disabled())
            return new HeldLightDecision(true, null);
        if(definition != null && definition.waterSensitive() && wet)
            return new HeldLightDecision(true, null);

        EntityLight base = definition != null ? definition.light() : null;
        if(base == null && stack.getItem() instanceof BlockItem blockItem)
            base = lightFromBlockState(blockItem.getBlock().defaultBlockState());
        if(override != null)
            return new HeldLightDecision(true, override.apply(base));
        if(definition == null && base == null)
            return HeldLightDecision.DEFER;
        return new HeldLightDecision(true, base == null || base.isDark() ? null : base);
    }

    public record HeldLightDecision(boolean authoritative, @Nullable EntityLight light) {
        public static final HeldLightDecision DEFER = new HeldLightDecision(false, null);
    }

    @Nullable
    private static EntityLight lightFromBlockState(BlockState state) {
        BlockStateWrapper wrapped = new BlockStateWrapper(state);
        int brightness = Math.clamp(Config.getEmissionBrightness(wrapped), 0, 15);
        if(brightness == 0) return null;
        ColorRGB4 color = Config.getLightColor(wrapped);
        EntityLight light = EntityLight.of(color.red4, color.green4, color.blue4, brightness);
        return light.isDark() ? null : light;
    }

    @Nullable
    private static EntityLight merge(@Nullable EntityLight first, @Nullable EntityLight second) {
        if(first == null) return second;
        if(second == null) return first;
        return DynamicLightMath.max(first, second);
    }

}
