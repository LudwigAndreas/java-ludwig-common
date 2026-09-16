package ru.ludwigandreas.observability.tracing;

/**
 * A thread-scoped "sample this one regardless of the configured probability" flag.
 *
 * <h2>Why a thread-local rather than something tidier</h2>
 *
 * <p>The decision this flag influences is head sampling: OpenTelemetry asks the {@code Sampler}
 * whether to record a trace at the moment the first span starts, and from then on the answer is
 * fixed for the whole trace. That means the hint has to be readable from inside
 * {@link ForceableSampler#shouldSample}, which receives only OpenTelemetry's own arguments - no
 * request, no Spring context, nothing this module could attach a value to.
 *
 * <p>The saving grace is how narrow the window is. The servlet filter that sets the flag runs
 * immediately before Spring's observation filter, which starts the server span synchronously on the
 * same thread; the flag is set and cleared within that one frame, so the usual thread-local hazards
 * - pooled threads, async handoff - do not arise. It is cleared in a {@code finally} regardless, so
 * a thread returned to the pool never carries a stale "force" into an unrelated request.
 */
public final class ForcedSamplingHint {

    private static final ThreadLocal<Boolean> FORCED = new ThreadLocal<>();

    private ForcedSamplingHint() {
    }

    /** Whether the current thread has been asked to force-sample. */
    public static boolean isForced() {
        return Boolean.TRUE.equals(FORCED.get());
    }

    /**
     * Marks the current thread as force-sampling until the returned scope is closed.
     *
     * <p>Must be used with try-with-resources: an unclosed scope makes every subsequent request on a
     * pooled thread sample at 100%, which is exactly the traffic surge the probability was there to
     * prevent.
     */
    public static Scope force() {
        FORCED.set(Boolean.TRUE);
        // remove(), not set(false): leaving a mapping behind keeps a per-thread entry alive on every
        // request thread forever, and an empty ThreadLocal map entry is the classic slow leak in a
        // container that recycles threads but never discards them.
        return FORCED::remove;
    }

    /** An active force-sampling window. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
