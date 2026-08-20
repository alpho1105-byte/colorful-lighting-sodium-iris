package dev.colorfullighting.compat.iris;

import net.irisshaders.iris.Iris;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks how completely each shader-pack pipeline was patched. While every
 * iris_UV2-consuming program is sanitized, colorful packed light may flow to the GPU
 * untouched; if any program could not be sanitized, the CPU-side decode fallback in
 * ColorfulLightGate must convert vanilla-pipeline light back to vanilla packed values.
 *
 * <p>Iris keeps one pipeline per dimension and constructs new ones without destroying
 * the old, so the counters cannot be a single global: one dimension's sanitize failure
 * would force CPU decode onto another dimension's fully tinted pipeline, and counts
 * would accumulate across constructions. Recording is bracketed per construction
 * (patching happens eagerly inside IrisRenderingPipeline's constructor, on the render
 * thread): {@link #beginConstruction()} clears the accumulators before the factory
 * runs, {@link #commit(Object)} snapshots them for that pipeline instance, and readers
 * resolve the snapshot of whichever pipeline is current. The map is identity-keyed by
 * pipeline instance and pruned in {@link #forget(Object)} from the destroy hook;
 * Iris's destroyPipeline destroys every cached pipeline, so entries cannot leak.
 */
public final class IrisPatchState {
    private static final AtomicInteger sanitizedPrograms = new AtomicInteger();
    private static final AtomicInteger tintedPrograms = new AtomicInteger();
    private static final AtomicInteger sanitizeFailures = new AtomicInteger();
    private static final AtomicInteger dynamicLightPrograms = new AtomicInteger();
    private static final AtomicInteger terrainTintPrograms = new AtomicInteger();

    public record Snapshot(
            int sanitized,
            int tinted,
            int failures,
            int dynamicLights,
            int terrainTint
    ) {
    }

    private static final Map<Object, Snapshot> snapshots = new ConcurrentHashMap<>();

    private IrisPatchState() {
    }

    /** Clears the construction accumulators (also drops residue of a failed constructor). */
    public static void beginConstruction() {
        sanitizedPrograms.set(0);
        tintedPrograms.set(0);
        sanitizeFailures.set(0);
        dynamicLightPrograms.set(0);
        terrainTintPrograms.set(0);
    }

    /** Snapshots the accumulated counts for the finished pipeline and clears them. */
    public static Snapshot commit(Object pipeline) {
        Snapshot snapshot = new Snapshot(
                sanitizedPrograms.get(),
                tintedPrograms.get(),
                sanitizeFailures.get(),
                dynamicLightPrograms.get(),
                terrainTintPrograms.get()
        );
        snapshots.put(pipeline, snapshot);
        beginConstruction();
        return snapshot;
    }

    public static void forget(Object pipeline) {
        snapshots.remove(pipeline);
    }

    public static void recordSanitized(boolean withTint) {
        sanitizedPrograms.incrementAndGet();
        if (withTint) {
            tintedPrograms.incrementAndGet();
        }
    }

    public static void recordDynamicLights() {
        dynamicLightPrograms.incrementAndGet();
    }

    public static void recordTerrainTint() {
        terrainTintPrograms.incrementAndGet();
    }

    public static void recordFailure() {
        sanitizeFailures.incrementAndGet();
    }

    /**
     * The committed snapshot of the pipeline currently rendering, or null before the
     * first pipeline exists (Iris publishes a pipeline only after its constructor -
     * and therefore its commit - completed, so a current pipeline always has one).
     */
    private static Snapshot currentSnapshot() {
        Object pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline == null ? null : snapshots.get(pipeline);
    }

    /** Number of programs whose per-pixel dynamic entity-light hook was injected. */
    public static int dynamicLightCount() {
        Snapshot snapshot = currentSnapshot();
        return snapshot == null ? 0 : snapshot.dynamicLights();
    }

    public static boolean cpuDecodeNeeded() {
        Snapshot snapshot = currentSnapshot();
        // unknown pipeline (vanilla fallback or pre-first-render): be conservative -
        // the caller additionally gates on isShaderPackInUse, so this stays inert
        // without a pack
        return snapshot == null || snapshot.failures() > 0;
    }
}
