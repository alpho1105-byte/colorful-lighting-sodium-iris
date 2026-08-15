package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.ClientAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.PlayerAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Class responsible for managing light color values in the client's world and sampling those values.
 * Most work is delegated to LightPropagator thread.
 */
public class ColoredLightEngine {
    private ClientAccessor clientAccessor;
    private ColoredLightStorage storage = new ColoredLightStorage();
    private ViewArea viewArea = new ViewArea();
    private final ConcurrentLinkedQueue<LightUpdateRequest> blockUpdateDecreaseRequests = new ConcurrentLinkedQueue<>(); // those first added will be executed first (this order is required by decrease propagation algorithm)
    private final ConcurrentLinkedQueue<BlockRequests> blockUpdateIncreaseRequests = new ConcurrentLinkedQueue<>(); // those nearest to the player will be executed first
    private final ConcurrentLinkedQueue<ChunkPos> chunksWaitingForPropagation = new ConcurrentLinkedQueue<>(); // those nearest to the player will be executed first
    private final Set<Long> dirtySections = new HashSet<>();
    private LightPropagator lightPropagator;
    private Thread lightPropagatorThread;

    private static ColoredLightEngine instance;
    public static ColoredLightEngine getInstance() {
        return instance;
    }
    public static void create(ClientAccessor clientAccessor) {
        instance = new ColoredLightEngine(clientAccessor);
    }

    private ColoredLightEngine(ClientAccessor clientAccessor) {
        this.clientAccessor = clientAccessor;
        reset();
    }

    public ColorRGB4 sampleLightColor(BlockPos pos) { return sampleLightColor(pos.getX(), pos.getY(), pos.getZ()); }
    public ColorRGB4 sampleLightColor(int x, int y, int z) {
        var entry = storage.getEntry(x, y, z);
        if(entry == null) return ColorRGB4.fromRGB4(0, 0, 0);
        return entry;
    }
    /**
     * Mixes light color from blocks neighbouring given position using trilinear interpolation.
     * Weights are the true fractional offsets and black corners participate in the blend,
     * so the sampled color fades smoothly with distance instead of snapping at range edges.
     */
    public ColorRGB8 sampleTrilinearLightColor(Vec3 pos) {
        double gridX = pos.x - 0.5;
        double gridY = pos.y - 0.5;
        double gridZ = pos.z - 0.5;
        int cornerX = (int) Math.floor(gridX);
        int cornerY = (int) Math.floor(gridY);
        int cornerZ = (int) Math.floor(gridZ);
        double deltaX = gridX - cornerX;
        double deltaY = gridY - cornerY;
        double deltaZ = gridZ - cornerZ;

        double red = 0.0, green = 0.0, blue = 0.0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    ColorRGB4 corner = sampleLightColor(cornerX + dx, cornerY + dy, cornerZ + dz);
                    double weight = (dx == 0 ? 1.0 - deltaX : deltaX)
                            * (dy == 0 ? 1.0 - deltaY : deltaY)
                            * (dz == 0 ? 1.0 - deltaZ : deltaZ);
                    red += corner.red4 * weight;
                    green += corner.green4 * weight;
                    blue += corner.blue4 * weight;
                }
            }
        }
        return ColorRGB8.fromRGB8(
                (int) Math.round(red * 17.0),
                (int) Math.round(green * 17.0),
                (int) Math.round(blue * 17.0)
        );
    }


    public void updateViewArea(ViewArea newArea) {
        LevelAccessor level = clientAccessor.getLevel();
        if(level == null) return;
        if(viewArea.equals(newArea)) return;

        // unload sections
        // remove propagation requests which are not in newArea's inner area
        blockUpdateIncreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos));
        blockUpdateDecreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos));
        chunksWaitingForPropagation.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z));
        // remove sections from storage
        for(int x = viewArea.minX; x <= viewArea.maxX; ++x) {
            for(int z = viewArea.minZ; z <= viewArea.maxZ; ++z) {
                if(newArea.contains(x, z)) continue;
                for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                    storage.removeSection(SectionPos.asLong(x, y, z));
                }
            }
        }

        // load sections
        // add sections to storage and queue chunks for propagation
        for(int x = newArea.minX; x <= newArea.maxX; ++x) {
            for(int z = newArea.minZ; z <= newArea.maxZ; ++z) {
                if(viewArea.containsInner(x, z)) continue; // old area already contains propagated section
                boolean viewAreaContainsOuter = viewArea.contains(x, z);
                if(!viewAreaContainsOuter) {
                    for(int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                        long pos = SectionPos.asLong(x, y, z);
                        storage.addSection(pos);
                    }
                }
                if(newArea.containsInner(x, z))
                    chunksWaitingForPropagation.add(new ChunkPos(x, z));
            }
        }
        viewArea = newArea;
    }

    public void onBlockLightPropertiesChanged(BlockPos blockPos) {
        LevelAccessor level = clientAccessor.getLevel();
        if(level == null) return;

        SectionPos sectionPos = SectionPos.of(blockPos);
        // light should be propagated only in inner chunks as
        // full propagation needs light source's chunk and neighbours
        if(!viewArea.containsInner(sectionPos.x(), sectionPos.z())) return;

        BlockRequests increaseRequests = new BlockRequests(blockPos);
        handleBlockUpdate(level, increaseRequests.increaseRequests, blockUpdateDecreaseRequests, blockPos);
        if(!increaseRequests.increaseRequests.isEmpty()) blockUpdateIncreaseRequests.add(increaseRequests);
    }
    private void handleBlockUpdate(LevelAccessor level, Queue<LightUpdateRequest> increaseRequests, Queue<LightUpdateRequest> decreaseRequests, BlockPos blockPos) {
        // read through the propagator's staging buffers, not just committed storage: a
        // freshly placed source may still be in flight, and seeding from the stale
        // committed value would skip the decrease wave and strand its light forever
        ColorRGB4 lightColor = lightPropagator.getLatestLightColor(blockPos);
        if(lightColor == null) return; // section not loaded
        if(lightColor.red4 == 0 && lightColor.green4 == 0 && lightColor.blue4 == 0)
            requestLightPullIn(increaseRequests, blockPos);  // block probably destroyed/replaced with transparent, light pull in might be needed
        else
            decreaseRequests.add(new LightUpdateRequest(blockPos, lightColor, false)); // block probably placed/replaced with non-transparent, light might need to be decreased

        // propagate light if new blockState emits light
        if(Config.getEmissionBrightness(level, blockPos, 0) > 0)
            increaseRequests.add(new LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos), false));
    }
    private void requestLightPullIn(Queue<LightUpdateRequest> increaseRequests, BlockPos blockPos) {
        for(var direction : Direction.values()) {
            BlockPos neighbourPos = blockPos.relative(direction);
            ColorRGB4 neighbourLight = lightPropagator.getLatestLightColor(neighbourPos);
            if(neighbourLight == null) continue;

            if(neighbourLight.red4 == 0 && neighbourLight.green4 == 0 && neighbourLight.blue4 == 0) continue;
            // color is resolved when the request is processed (after pending decreases),
            // otherwise a value captured now can resurrect light another in-flight
            // decrease wave is about to remove
            increaseRequests.add(new LightUpdateRequest(neighbourPos, null, true));
        }
    }

    public void onLightUpdate() {
        LevelAccessor level = clientAccessor.getLevel();
        if(level == null) return;

        lightPropagator.applyReadyLightChanges();

        // set all modified sections dirty
        synchronized (dirtySections) {
            for (Long dirtySection : dirtySections) {
                SectionPos sectionPos = SectionPos.of(dirtySection);
                level.setSectionDirty(sectionPos.x(), sectionPos.y(), sectionPos.z());
            }
            dirtySections.clear();
        }
    }

    public void reset() {
        if(lightPropagator != null) {
            lightPropagator.stop();
            try {
                lightPropagatorThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // still rebuild the engine below
            }
        }
        storage.clear();
        viewArea = new ViewArea();
        dirtySections.clear();
        blockUpdateIncreaseRequests.clear();
        blockUpdateDecreaseRequests.clear();
        chunksWaitingForPropagation.clear();
        EntityLightManager.reset(); // tracked entity emitters are re-added on the next tick
        lightPropagator = new LightPropagator();
        lightPropagatorThread = new Thread(lightPropagator, "ColorfulLighting-LightPropagator");
        lightPropagatorThread.setDaemon(true);
        lightPropagatorThread.start();
        ColorfulLighting.LOGGER.info("Colored light engine reset");
    }

    /**
     * LightPropagator calculates changes to light values. It runs on another thread to avoid lag on the main thread.
     * It propagates increases (increases of light values, e.g. new light source has been placed).
     * It propagates decreases (decreases of light values, e.g. light source has been destroyed, solid block has been placed in the path of light).
     * Changes caused by block updates are applied on the main thread to avoid light flickering
     */
    private class LightPropagator implements Runnable {
        /**
         * light changes that are not yet ready to be visible on main thread
         */
        private ConcurrentHashMap<BlockPos, ColorRGB4> lightChangesInProgress = new ConcurrentHashMap<>();
        /**
         * light changes ready to be visible on main thread
         */
        private final ConcurrentHashMap<BlockPos, ColorRGB4> lightChangesReady = new ConcurrentHashMap<>();
        private final Lock lightChangesReadyLock = new ReentrantLock();
        private volatile boolean running;

        @Override
        public void run() {
            running = true;
            while (running) {
                try {
                    propagateLight();
                } catch (Exception e) {
                    // a dying worker would silently freeze colored lighting while the
                    // request queues keep growing; log and keep the loop alive
                    ColorfulLighting.LOGGER.error("Colored light propagation failed", e);
                }

                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        public void stop() {
            running = false;
        }

        private void addLightColorChange(BlockPos blockPos, ColorRGB4 color) {
            lightChangesInProgress.put(blockPos, color);
        }

        public ColorRGB4 getLatestLightColor(BlockPos blockPos) {
            // check the staging buffers before storage: evaluating storage first (as the
            // old nested getOrDefault did) races with the apply+clear on the client
            // thread and can return a pre-commit value
            ColorRGB4 inProgress = lightChangesInProgress.get(blockPos);
            if(inProgress != null) return inProgress;
            ColorRGB4 ready = lightChangesReady.get(blockPos);
            if(ready != null) return ready;
            return storage.getEntry(blockPos);
        }

        private record NearestBlockRequestsResult(BlockRequests blockUpdate, int distanceBlocks) {}
        private NearestBlockRequestsResult getNearestBlockRequests(PlayerAccessor player) {
            // find chunk nearest player
            var iterator = blockUpdateIncreaseRequests.iterator();
            int minDistance = Integer.MAX_VALUE;
            BlockRequests nearestUpdate = null;
            while (iterator.hasNext()) {
                BlockRequests update = iterator.next();
                int distance = update.blockPos.distManhattan(player.getBlockPos());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestUpdate = update;
                }
            }
            return nearestUpdate == null ? null : new NearestBlockRequestsResult(nearestUpdate, minDistance);
        }

        private record NearestChunkResult(ChunkPos chunkPos, int distanceBlocks) {}
        private NearestChunkResult getNearestWaitingChunk(LevelAccessor level, PlayerAccessor player) {
            // find chunk nearest player
            var iterator = chunksWaitingForPropagation.iterator();
            int minDistance = Integer.MAX_VALUE;
            ChunkPos nearestChunkPos = null;
            while (iterator.hasNext()) {
                ChunkPos chunkPos = iterator.next();
                if(!level.hasChunkAndNeighbours(chunkPos)) continue; // chunk and neighbours must have available block state data
                int distance = chunkPos.getChessboardDistance(player.getChunkPos());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestChunkPos = chunkPos;
                }
            }
            return nearestChunkPos == null ? null : new NearestChunkResult(nearestChunkPos, minDistance * 16); // distanceBlocks is in blocks
        }

        /**
         * apply ready light changes to storage
         */
        private void applyReadyLightChanges() {
            lightChangesReadyLock.lock();
            synchronized (dirtySections) {
                for (var entry : lightChangesReady.entrySet()) {
                    storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                    SectionPos.aroundAndAtBlockPos(entry.getKey(), dirtySections::add);
                }
                lightChangesReady.clear();
            }
            lightChangesReadyLock.unlock();
        }

        /**
         * move light changes in progress to collection of ready light changes
         */
        private void markLightChangesReady() {
            lightChangesReadyLock.lock();
            lightChangesReady.putAll(lightChangesInProgress);
            lightChangesReadyLock.unlock();
            lightChangesInProgress = new ConcurrentHashMap<>();
        }

        /**
         * apply light changes in progress directly to storage
         */
        private void applyLightChangesDirectly() {
            // hold the ready lock so worker-side commits never interleave with the
            // client thread's applyReadyLightChanges writing the same sections
            lightChangesReadyLock.lock();
            try {
                for (var entry : lightChangesInProgress.entrySet()) {
                    storage.setEntryUnsafe(entry.getKey(), entry.getValue());
                    synchronized (dirtySections) {
                        SectionPos.aroundAndAtBlockPos(entry.getKey(), dirtySections::add);
                    }
                }
                lightChangesInProgress.clear();
            } finally {
                lightChangesReadyLock.unlock();
            }
        }

        /**
         * propagate light in the nearest waiting chunk, handle block light updates
         */
        private void propagateLight() {
            LevelAccessor level = clientAccessor.getLevel();
            if(level == null) return;
            PlayerAccessor player = clientAccessor.getPlayer();
            if(player == null) return;

            // decrease requests are always executed
            if(!blockUpdateDecreaseRequests.isEmpty()) {
                Queue<LightUpdateRequest> newIncreaseRequests = new LinkedList<>();
                propagateDecreases(level, blockUpdateDecreaseRequests, newIncreaseRequests);
                propagateIncreases(level, newIncreaseRequests);

                markLightChangesReady();
            }
            
            var nearestChunkResult = getNearestWaitingChunk(level, player);
            var nearestBlockRequests = getNearestBlockRequests(player);

            if(nearestChunkResult != null && (nearestBlockRequests == null || nearestChunkResult.distanceBlocks() < nearestBlockRequests.distanceBlocks())) {
                // propagate chunk
                ChunkPos chunkPos = nearestChunkResult.chunkPos();
                chunksWaitingForPropagation.remove(chunkPos);

                Queue<LightUpdateRequest> increaseRequests = new LinkedList<>();
                // find light sources and request their propagation
                level.findLightSources(chunkPos, (blockPos -> {
                    increaseRequests.add(new LightUpdateRequest(blockPos, Config.getColorEmission(level, blockPos), false));
                }));
                propagateIncreases(level, increaseRequests);
                // new chunks' light propagation is not synchronized with main thread
                applyLightChangesDirectly();
            }
            else if(nearestBlockRequests != null) {
                blockUpdateIncreaseRequests.remove(nearestBlockRequests.blockUpdate);
                propagateIncreases(level, nearestBlockRequests.blockUpdate.increaseRequests);
                markLightChangesReady();
            }
        }

        /**
         * Handles all increase propagation requests.
         */
        private void propagateIncreases(LevelAccessor level, Queue<LightUpdateRequest> requests) {
            while(!requests.isEmpty()) {
                propagateIncrease(requests, requests.poll(), level);
            }
        }

        private boolean propagateIncrease(Queue<LightUpdateRequest> increaseRequests, LightUpdateRequest request, LevelAccessor level) {
            ColorRGB4 oldLightColor = getLatestLightColor(request.blockPos);
            if(oldLightColor == null) return false; // section might have got unloaded and propagation should stop
            ColorRGB4 sourceColor = request.lightColor;
            if(sourceColor == null) {
                // deferred pull-in: resolve the color only now, after every pending
                // decrease wave has run, so a value removed in the same batch cannot
                // be captured and resurrected
                sourceColor = oldLightColor;
                if(sourceColor.red4 == 0 && sourceColor.green4 == 0 && sourceColor.blue4 == 0) return true;
            }
            ColorRGB4 newLightColor = ColorRGB4.fromRGB4(
                    Math.max(oldLightColor.red4, sourceColor.red4),
                    Math.max(oldLightColor.green4, sourceColor.green4),
                    Math.max(oldLightColor.blue4, sourceColor.blue4)
            );

            // if light color didn't change (check is ignored if request is forced)
            if(!request.force && newLightColor.red4 == oldLightColor.red4 && newLightColor.green4 == oldLightColor.green4 && newLightColor.blue4 == oldLightColor.blue4) return true;
            addLightColorChange(request.blockPos, newLightColor);

            for(var direction : Direction.values()) {
                BlockPos neighbourPos = request.blockPos.relative(direction);
                if(!level.isInBounds(neighbourPos)) continue;
                BlockStateAccessor neighbourState = level.getBlockState(neighbourPos);
                if(neighbourState == null) return false; // section might have got unloaded and propagation should stop

                // light attenuation
                int lightBlocked = Math.max(1, neighbourState.getLightBlock(level, neighbourPos)); // vanilla light block
                ColorRGB4 coloredLightTransmittance = Config.getColoredLightTransmittance(level, neighbourPos, neighbourState); // rgb transmittance (example: red stained glass can let only red light through)
                ColorRGB4 neighbourLightColor = ColorRGB4.fromRGB4(
                        Math.clamp(sourceColor.red4 - lightBlocked, 0, coloredLightTransmittance.red4),
                        Math.clamp(sourceColor.green4 - lightBlocked, 0, coloredLightTransmittance.green4),
                        Math.clamp(sourceColor.blue4 - lightBlocked, 0, coloredLightTransmittance.blue4)
                );
                // if no more color to propagate
                if(neighbourLightColor.red4 == 0 && neighbourLightColor.green4 == 0 && neighbourLightColor.blue4 == 0) continue;

                increaseRequests.add(new LightUpdateRequest(neighbourPos, neighbourLightColor, false));
            }
            return true;
        }

        /**
         * Handles all decrease propagation requests.
         */
        private void propagateDecreases(LevelAccessor level, Queue<LightUpdateRequest> decreaseRequests, Queue<LightUpdateRequest> increaseRequests) {
            while(!decreaseRequests.isEmpty()) {
                propagateDecrease(increaseRequests, decreaseRequests, decreaseRequests.poll(), level);
            }
        }

        private boolean propagateDecrease(Queue<LightUpdateRequest> increaseRequests, Queue<LightUpdateRequest> decreaseRequests, LightUpdateRequest request, LevelAccessor level) {
            ColorRGB4 oldLightColor = getLatestLightColor(request.blockPos);
            if(oldLightColor == null) return false; // section might have got unloaded and propagation should stop

            // if light color didn't change (check is ignored if request is forced)
            if(!request.force && oldLightColor.red4 == 0 && oldLightColor.green4 == 0 && oldLightColor.blue4 == 0) return true;
            addLightColorChange(request.blockPos, ColorRGB4.fromRGB4(0, 0, 0));

            BlockStateAccessor blockState = level.getBlockState(request.blockPos);
            if(blockState == null) return false; // section might have got unloaded and propagation should stop
            // repropagate removed light
            if(Config.getEmissionBrightness(level, request.blockPos, blockState) > 0) {
                increaseRequests.add(new LightUpdateRequest(request.blockPos, Config.getColorEmission(level, request.blockPos), false));
            }

            // attenuation
            ColorRGB4 neighbourLightDecrease = ColorRGB4.fromRGB4(
                    Math.max(0, request.lightColor.red4 - 1),
                    Math.max(0, request.lightColor.green4 - 1),
                    Math.max(0, request.lightColor.blue4 - 1)
            );
            // whether neighbours' light should be decreased or increased (to repropagate), true on "light edges"
            boolean repropagateNeighbours = neighbourLightDecrease.red4 == 0 && neighbourLightDecrease.green4 == 0 && neighbourLightDecrease.blue4 == 0;

            for(var direction : Direction.values()) {
                BlockPos neighbourPos = request.blockPos.relative(direction);
                if(!level.isInBounds(neighbourPos)) continue;

                if(!repropagateNeighbours) {
                    // propagate decrease
                    decreaseRequests.add(new LightUpdateRequest(neighbourPos, neighbourLightDecrease, false));
                }
                else {
                    ColorRGB4 neighbourLightColor = getLatestLightColor(neighbourPos);
                    if(neighbourLightColor == null) return false; // section might have got unloaded and propagation should stop
                    // if neighbour doesn't have any light
                    if(neighbourLightColor.red4 == 0 && neighbourLightColor.green4 == 0 && neighbourLightColor.blue4 == 0)
                        continue;

                    // force neighbour to propagate light to the region that has been just
                    // cleared (color resolved at processing time, after all decreases)
                    increaseRequests.add(new LightUpdateRequest(neighbourPos, null, true));
                }
            }
            return true;
        }
    }

    private static class BlockRequests {
        public BlockPos blockPos;
        public Queue<LightUpdateRequest> increaseRequests = new LinkedList<>();

        public BlockRequests(BlockPos blockPos) {
            this.blockPos = blockPos;
        }
    }

    private static class LightUpdateRequest {
        BlockPos blockPos;
        ColorRGB4 lightColor;
        boolean force;

        public LightUpdateRequest(BlockPos blockPos, ColorRGB4 lightColor, boolean force) {
            this.blockPos = blockPos;
            this.lightColor = lightColor;
            this.force = force;
        }
    }
}
