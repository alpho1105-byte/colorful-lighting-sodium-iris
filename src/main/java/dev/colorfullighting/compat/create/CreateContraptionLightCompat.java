package dev.colorfullighting.compat.create;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.accessors.BlockStateWrapper;
import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * Optional Create adapter. All Create types are discovered by name so merely loading
 * Colorful Lighting never links against Create and works unchanged when it is absent.
 */
public final class CreateContraptionLightCompat {
    private static final String ENTITY_CLASS =
            "com.simibubi.create.content.contraptions.AbstractContraptionEntity";
    private static final String CONTRAPTION_CLASS =
            "com.simibubi.create.content.contraptions.Contraption";
    private static final int RESCAN_INTERVAL_TICKS = 10;

    private static final WeakHashMap<Entity, CachedContraption> CACHE = new WeakHashMap<>();
    private static boolean initialized;
    private static boolean available;
    private static boolean invocationFailureLogged;
    private static Class<?> contraptionEntityClass;
    private static Method getContraption;
    private static Method getBlocks;
    private static Method toGlobalVector;

    private CreateContraptionLightCompat() {
    }

    public static void collect(
            Entity entity,
            long gameTime,
            BiConsumer<Vec3, EntityLight> sink
    ) {
        initialize();
        if(!available || !contraptionEntityClass.isInstance(entity)) return;

        try {
            Object contraption = getContraption.invoke(entity);
            if(contraption == null) return;
            Object rawBlocks = getBlocks.invoke(contraption);
            if(!(rawBlocks instanceof Map<?, ?> blocks)) return;

            CachedContraption cached = CACHE.computeIfAbsent(entity, unused -> new CachedContraption());
            if(cached.contraption != contraption
                    || cached.blockCount != blocks.size()
                    || gameTime >= cached.nextRescan) {
                cached.rebuild(contraption, blocks, gameTime);
            }

            for(BlockPos localPos : cached.lightPositions) {
                Object value = blocks.get(localPos);
                if(!(value instanceof StructureTemplate.StructureBlockInfo info)) continue;
                EntityLight light = lightFrom(info.state());
                if(light == null) continue;
                Vec3 localCenter = Vec3.atCenterOf(localPos);
                Object transformed = toGlobalVector.invoke(entity, localCenter, 1.0f);
                if(transformed instanceof Vec3 worldPosition)
                    sink.accept(worldPosition, light);
            }
        }
        catch (ReflectiveOperationException | RuntimeException exception) {
            if(!invocationFailureLogged) {
                invocationFailureLogged = true;
                ColorfulLighting.LOGGER.warn(
                        "Create contraption light integration failed; disabling it for this session",
                        exception
                );
            }
            available = false;
            CACHE.clear();
        }
    }

    public static void reset() {
        CACHE.clear();
    }

    private static void initialize() {
        if(initialized) return;
        initialized = true;
        try {
            ClassLoader loader = CreateContraptionLightCompat.class.getClassLoader();
            contraptionEntityClass = Class.forName(ENTITY_CLASS, false, loader);
            Class<?> contraptionClass = Class.forName(CONTRAPTION_CLASS, false, loader);
            getContraption = contraptionEntityClass.getMethod("getContraption");
            getBlocks = contraptionClass.getMethod("getBlocks");
            toGlobalVector = contraptionEntityClass.getMethod("toGlobalVector", Vec3.class, float.class);
            available = true;
            ColorfulLighting.LOGGER.info("Enabled optional Create contraption light integration");
        }
        catch (ClassNotFoundException absent) {
            available = false;
        }
        catch (ReflectiveOperationException incompatible) {
            available = false;
            ColorfulLighting.LOGGER.warn(
                    "Create is present but its contraption API is not compatible with colored moving lights",
                    incompatible
            );
        }
    }

    private static EntityLight lightFrom(BlockState state) {
        BlockStateWrapper wrapped = new BlockStateWrapper(state);
        int brightness = Math.clamp(Config.getEmissionBrightness(wrapped), 0, 15);
        if(brightness == 0) return null;
        ColorRGB4 color = Config.getLightColor(wrapped);
        EntityLight light = EntityLight.of(color.red4, color.green4, color.blue4, brightness);
        return light.isDark() ? null : light;
    }

    private static final class CachedContraption {
        private Object contraption;
        private int blockCount;
        private long nextRescan;
        private List<BlockPos> lightPositions = List.of();

        private void rebuild(Object currentContraption, Map<?, ?> blocks, long gameTime) {
            List<BlockPos> positions = new ArrayList<>();
            for(Object value : blocks.values()) {
                if(!(value instanceof StructureTemplate.StructureBlockInfo info)) continue;
                if(lightFrom(info.state()) != null) positions.add(info.pos().immutable());
            }
            contraption = currentContraption;
            blockCount = blocks.size();
            nextRescan = gameTime + RESCAN_INTERVAL_TICKS;
            lightPositions = List.copyOf(positions);
        }
    }
}
