package dev.colorfullighting.compat.iris;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks how completely the current shader-pack pipeline was patched. While every
 * iris_UV2-consuming program is sanitized, colorful packed light may flow to the GPU
 * untouched; if any program could not be sanitized, the CPU-side decode fallback in
 * ColorfulLightGate must convert vanilla-pipeline light back to vanilla packed values.
 */
public final class IrisPatchState {
    private static final AtomicInteger sanitizedPrograms = new AtomicInteger();
    private static final AtomicInteger tintedPrograms = new AtomicInteger();
    private static final AtomicInteger sanitizeFailures = new AtomicInteger();
    private static final AtomicInteger dynamicLightPrograms = new AtomicInteger();

    private IrisPatchState() {
    }

    public static void reset() {
        sanitizedPrograms.set(0);
        tintedPrograms.set(0);
        sanitizeFailures.set(0);
        dynamicLightPrograms.set(0);
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

    /** Number of programs whose per-pixel dynamic entity-light hook was injected. */
    public static int dynamicLightCount() {
        return dynamicLightPrograms.get();
    }

    public static void recordFailure() {
        sanitizeFailures.incrementAndGet();
    }

    public static boolean cpuDecodeNeeded() {
        return sanitizeFailures.get() > 0;
    }

    public static int sanitizedCount() {
        return sanitizedPrograms.get();
    }

    public static int tintedCount() {
        return tintedPrograms.get();
    }

    public static int failureCount() {
        return sanitizeFailures.get();
    }
}
