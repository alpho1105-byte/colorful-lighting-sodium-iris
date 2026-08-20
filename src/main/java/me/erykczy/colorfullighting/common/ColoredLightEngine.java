package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.ClientAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.PlayerAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.TrilinearLightSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Class responsible for managing light color values in the client's world and sampling those values.
 * Most work is delegated to LightPropagator thread.
 */
public class ColoredLightEngine {
    private static final int INITIAL_REMESH_RADIUS_CHUNKS = 2;

    private ClientAccessor clientAccessor;
    private ColoredLightStorage storage = new ColoredLightStorage();
    private ViewArea viewArea = new ViewArea();
    private final ConcurrentLinkedQueue<LightUpdateRequest> blockUpdateDecreaseRequests = new ConcurrentLinkedQueue<>(); // those first added will be executed first (this order is required by decrease propagation algorithm)
    private final ConcurrentLinkedQueue<BlockRequests> blockUpdateIncreaseRequests = new ConcurrentLinkedQueue<>(); // those nearest to the player will be executed first
    private final ConcurrentLinkedQueue<ChunkPos> chunksWaitingForPropagation = new ConcurrentLinkedQueue<>(); // those nearest to the player will be executed first
    private final Set<Long> dirtySections = new HashSet<>();
    private volatile boolean initialPropagationActive;
    private final AtomicBoolean initialRemeshPending = new AtomicBoolean();
    private LightPropagator lightPropagator;
    private Thread lightPropagatorThread;

    private static ColoredLightEngine instance;
    public static ColoredLightEngine getInstance() {
        return instance;
    }
    public static void create(ClientAccessor clientAccessor) {
        instance = new ColoredLightEngine(clientAccessor);
    }

    // hoisted: a per-sample lambda would allocate on every terrain vertex
    private final TrilinearLightSampler.PackedLookup packedStorageLookup =
            (x, y, z) -> storage.getPackedEntry(x, y, z);
    /** Missing sections read as black - the legacy non-null sampling contract. */
    private final TrilinearLightSampler.PackedLookup lenientStorageLookup = (x, y, z) -> {
        int packed = storage.getPackedEntry(x, y, z);
        return packed == ColoredLightStorage.MISSING ? 0 : packed;
    };
    // world block-Y bounds of the current level, cached on the view-area update so the
    // per-vertex sampling path does not fetch the level per call (volatile: read by
    // meshing workers). MIN/MAX sentinels until the first update.
    private volatile int minBlockY = Integer.MIN_VALUE;
    private volatile int maxBlockY = Integer.MAX_VALUE;

    private ColoredLightEngine(ClientAccessor clientAccessor) {
        this.clientAccessor = clientAccessor;
        reset();
    }

    public ColorRGB4 sampleLightColor(BlockPos pos) { return sampleLightColor(pos.getX(), pos.getY(), pos.getZ()); }
    public ColorRGB4 sampleLightColor(int x, int y, int z) {
        ColorRGB4 stored = sampleStoredLightColor(x, y, z);
        ColorRGB4 dynamic = EntityLightManager.sampleLightColor(x + 0.5, y + 0.5, z + 0.5);
        return maxChannels(stored, dynamic);
    }

    private ColorRGB4 sampleStoredLightColor(int x, int y, int z) {
        int packed = storage.getPackedEntry(x, y, z);
        if(packed <= 0) return ColorRGB4.fromRGB4(0, 0, 0); // missing or black
        return ColorRGB4.fromRGB4((packed >>> 8) & 0x0F, (packed >>> 4) & 0x0F, packed & 0x0F);
    }

    /** True when this position belongs to an allocated colored-light section. */
    public boolean hasLightData(BlockPos pos) {
        return storage.containsEntry(pos);
    }

    /**
     * True when every section touched by a trilinear sample is available. Falling
     * back when even one corner is missing prevents an absent section from being
     * interpreted as real black light at view-area or virtual-sublevel boundaries.
     */
    public boolean hasLightData(Vec3 pos) {
        return hasLightData(pos.x, pos.y, pos.z);
    }

    /** Primitive overload for the per-vertex meshing path (avoids a Vec3 per vertex). */
    public boolean hasLightData(double posX, double posY, double posZ) {
        int cornerX = (int) Math.floor(posX - 0.5);
        int cornerY = (int) Math.floor(posY - 0.5);
        int cornerZ = (int) Math.floor(posZ - 0.5);
        // Corners above/below the world's section range are legitimately dark, not
        // missing data: storage never allocates there, and treating them as absent made
        // every sample touching the build limits fall back to vanilla light, drawing a
        // color seam along top faces at max height and bottom faces at min height.
        for(int dx = 0; dx <= 1; dx++) {
            for(int dy = 0; dy <= 1; dy++) {
                int y = cornerY + dy;
                if(y < minBlockY || y > maxBlockY) continue;
                for(int dz = 0; dz <= 1; dz++) {
                    if(!storage.containsEntry(cornerX + dx, y, cornerZ + dz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Mixes light color from blocks neighbouring given position using trilinear interpolation.
     * Weights are the true fractional offsets and black corners participate in the blend,
     * so the sampled color fades smoothly with distance instead of snapping at range edges.
     */
    public ColorRGB8 sampleTrilinearLightColor(Vec3 pos) {
        return sampleTrilinearLightColor(pos.x, pos.y, pos.z);
    }

    /** Legacy non-null contract: missing corners read as black instead of aborting. */
    public ColorRGB8 sampleTrilinearLightColor(double posX, double posY, double posZ) {
        ColorRGB8 stored = TrilinearLightSampler.sampleOrNull(
                posX, posY, posZ, Integer.MIN_VALUE, Integer.MAX_VALUE, lenientStorageLookup
        );
        return combineWithDynamic(stored, posX, posY, posZ);
    }

    /**
     * Fused presence check + sample for the per-vertex meshing path: one corner walk
     * instead of the previous hasLightData + sample pair (halves the section probes).
     * Null when any in-world corner's section is unallocated - the caller keeps its
     * vanilla light, exactly like the old gate. Corners beyond the build-height
     * limits are legitimately dark, not missing (see hasLightData).
     */
    @Nullable
    public ColorRGB8 trySampleTrilinearLightColor(double posX, double posY, double posZ) {
        ColorRGB8 stored = TrilinearLightSampler.sampleOrNull(
                posX, posY, posZ, minBlockY, maxBlockY, packedStorageLookup
        );
        if(stored == null) return null;
        return combineWithDynamic(stored, posX, posY, posZ);
    }

    private static ColorRGB8 combineWithDynamic(ColorRGB8 stored, double posX, double posY, double posZ) {
        ColorRGB4 dynamic4 = EntityLightManager.sampleLightColor(posX, posY, posZ);
        if(dynamic4.red4 == 0 && dynamic4.green4 == 0 && dynamic4.blue4 == 0) return stored;
        ColorRGB8 dynamic = ColorRGB8.fromRGB4(dynamic4);
        return ColorRGB8.fromRGB8(
                Math.max(stored.red, dynamic.red),
                Math.max(stored.green, dynamic.green),
                Math.max(stored.blue, dynamic.blue)
        );
    }

    private static ColorRGB4 maxChannels(ColorRGB4 first, ColorRGB4 second) {
        return ColorRGB4.fromRGB4(
                Math.max(first.red4, second.red4),
                Math.max(first.green4, second.green4),
                Math.max(first.blue4, second.blue4)
        );
    }


    public void updateViewArea(ViewArea newArea) {
        LevelAccessor level = clientAccessor.getLevel();
        if(level == null) return;
        if(viewArea.equals(newArea)) return;
        boolean initializing = viewArea.maxX < viewArea.minX || viewArea.maxZ < viewArea.minZ;

        // unload sections
        // remove propagation requests which are not in newArea's inner area
        // increases stay inner-pruned: an increase executing after its chunk turned
        // border would commit a wave truncated at the unallocated ring and freeze it
        // (see onBlockLightPropertiesChanged); the pruned source heals on the border-
        // to-inner transition. Decreases only remove light and stay valid area-wide.
        blockUpdateIncreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos));
        blockUpdateDecreaseRequests.removeIf(blockUpdate -> !newArea.containsBlock(blockUpdate.blockPos));
        chunksWaitingForPropagation.removeIf(chunkPos -> !newArea.containsInner(chunkPos.x, chunkPos.z));

        // one dense-index republish handles both unload and load: overlapping columns
        // keep their section objects (and light data), everything else starts fresh
        minBlockY = level.getMinSectionY() << 4;
        maxBlockY = (level.getMaxSectionY() << 4) + 15;
        storage.rebuild(
                newArea.minX, newArea.maxX,
                level.getMinSectionY(), level.getMaxSectionY(),
                newArea.minZ, newArea.maxZ
        );

        // queue newly-inner chunks for propagation
        for(int x = newArea.minX; x <= newArea.maxX; ++x) {
            for(int z = newArea.minZ; z <= newArea.maxZ; ++z) {
                if(viewArea.containsInner(x, z)) continue; // old area already contains propagated section
                if(newArea.containsInner(x, z))
                    chunksWaitingForPropagation.add(new ChunkPos(x, z));
            }
        }
        viewArea = newArea;
        if(initializing && !chunksWaitingForPropagation.isEmpty())
            initialPropagationActive = true;
    }

    public void onBlockLightPropertiesChanged(BlockPos blockPos) {
        LevelAccessor level = clientAccessor.getLevel();
        if(level == null) return;

        SectionPos sectionPos = SectionPos.of(blockPos);
        // Decreases and pull-ins run for the whole allocated area, border included: a
        // source removed in a border chunk must still launch its removal wave, or its
        // stored light survives as a permanent ghost (the outer-to-inner transition
        // re-propagates increases only, and propagateIncrease never lowers a value).
        // New-emission increases stay inner-only: a source wave truncated at the
        // unallocated ring commits its cells anyway, and the unchanged-value early-out
        // in propagateIncrease then freezes the dark ring forever.
        if(!viewArea.contains(sectionPos.x(), sectionPos.z())) return;
        boolean inner = viewArea.containsInner(sectionPos.x(), sectionPos.z());

        BlockRequests increaseRequests = new BlockRequests(blockPos);
        handleBlockUpdate(level, increaseRequests.increaseRequests, blockUpdateDecreaseRequests, blockPos, inner);
        if(!increaseRequests.increaseRequests.isEmpty()) blockUpdateIncreaseRequests.add(increaseRequests);
    }

    /**
     * Requests remeshing for every loaded render section touched by a continuous
     * moving light. No colored-light propagation is enqueued: render sampling reads
     * the source's exact position directly.
     */
    public void markDynamicLightDirty(Vec3 position, int radius) {
        LevelAccessor level = clientAccessor.getLevel();
        if(level == null || radius <= 0) return;

        int minSectionX = SectionPos.blockToSectionCoord((int) Math.floor(position.x - radius));
        int maxSectionX = SectionPos.blockToSectionCoord((int) Math.floor(position.x + radius));
        int minSectionY = Math.max(
                level.getMinSectionY(),
                SectionPos.blockToSectionCoord((int) Math.floor(position.y - radius))
        );
        int maxSectionY = Math.min(
                level.getMaxSectionY(),
                SectionPos.blockToSectionCoord((int) Math.floor(position.y + radius))
        );
        int minSectionZ = SectionPos.blockToSectionCoord((int) Math.floor(position.z - radius));
        int maxSectionZ = SectionPos.blockToSectionCoord((int) Math.floor(position.z + radius));

        synchronized (dirtySections) {
            for(int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for(int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    if(!viewArea.contains(sectionX, sectionZ)) continue;
                    for(int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                        if(sectionIntersectsLight(sectionX, sectionY, sectionZ, position, radius))
                            dirtySections.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
                    }
                }
            }
        }
    }

    private static boolean sectionIntersectsLight(
            int sectionX,
            int sectionY,
            int sectionZ,
            Vec3 position,
            int radius
    ) {
        double distanceX = distanceToInterval(
                position.x,
                SectionPos.sectionToBlockCoord(sectionX),
                SectionPos.sectionToBlockCoord(sectionX + 1)
        );
        double distanceY = distanceToInterval(
                position.y,
                SectionPos.sectionToBlockCoord(sectionY),
                SectionPos.sectionToBlockCoord(sectionY + 1)
        );
        double distanceZ = distanceToInterval(
                position.z,
                SectionPos.sectionToBlockCoord(sectionZ),
                SectionPos.sectionToBlockCoord(sectionZ + 1)
        );
        return distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ
                <= radius * radius;
    }

    private static double distanceToInterval(double value, double min, double max) {
        if(value < min) return min - value;
        if(value > max) return value - max;
        return 0.0;
    }
    private void handleBlockUpdate(LevelAccessor level, Queue<LightUpdateRequest> increaseRequests, Queue<LightUpdateRequest> decreaseRequests, BlockPos blockPos, boolean allowNewEmission) {
        // read through the propagator's staging buffers, not just committed storage: a
        // freshly placed source may still be in flight, and seeding from the stale
        // committed value would skip the decrease wave and strand its light forever
        ColorRGB4 lightColor = lightPropagator.getLatestLightColor(blockPos);
        if(lightColor == null) return; // section not loaded
        if(lightColor.red4 == 0 && lightColor.green4 == 0 && lightColor.blue4 == 0)
            requestLightPullIn(increaseRequests, blockPos);  // block probably destroyed/replaced with transparent, light pull in might be needed
        else
            decreaseRequests.add(new LightUpdateRequest(blockPos, lightColor, false)); // block probably placed/replaced with non-transparent, light might need to be decreased

        // propagate light if new blockState emits light (inner chunks only, see caller)
        if(allowNewEmission && Config.getEmissionBrightness(level, blockPos, 0) > 0)
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

        // Initial chunk meshes can finish after their first dirty notification and
        // publish vertices sampled before colored propagation completed. Rebuild once
        // after the nearby initial seed is committed, matching the corrective effect
        // of an Iris shader reload without requiring the player to press R.
        if(initialRemeshPending.compareAndSet(true, false)) {
            level.rebuildAllSections();
            ColorfulLighting.LOGGER.info(
                    "Nearby initial colored-light propagation complete; rebuilt render sections"
            );
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
        // back to sentinels: stale bounds from a previous dimension would classify
        // out-of-range corners as "legal black" while the empty storage should read
        // as missing everywhere until the first view-area update
        minBlockY = Integer.MIN_VALUE;
        maxBlockY = Integer.MAX_VALUE;
        viewArea = new ViewArea();
        dirtySections.clear();
        blockUpdateIncreaseRequests.clear();
        blockUpdateDecreaseRequests.clear();
        chunksWaitingForPropagation.clear();
        initialPropagationActive = false;
        initialRemeshPending.set(false);
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
        // volatile: reassigned by the propagator thread in markLightChangesReady after
        // the ready lock is already released, while the client thread reads it lock-free
        // in getLatestLightColor; without the fence the client can see a stale map and
        // seed a decrease wave with (0,0,0), stranding the removed source's light
        private volatile ConcurrentHashMap<BlockPos, ColorRGB4> lightChangesInProgress = new ConcurrentHashMap<>();
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

        private boolean hasNearbyChunkWaitingForInitialPropagation(ChunkPos playerChunk) {
            for(ChunkPos chunkPos : chunksWaitingForPropagation) {
                if(chunkPos.getChessboardDistance(playerChunk) <= INITIAL_REMESH_RADIUS_CHUNKS)
                    return true;
            }
            return false;
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
                if(initialPropagationActive
                        && !hasNearbyChunkWaitingForInitialPropagation(player.getChunkPos())) {
                    initialPropagationActive = false;
                    initialRemeshPending.set(true);
                }
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
                    // a missing neighbour section (unallocated ring / unloaded) is just
                    // dark; aborting the loop here would leave the remaining directions
                    // of this edge block never repropagated
                    if(neighbourLightColor == null) continue;
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
