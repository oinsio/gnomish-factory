package com.github.oinsio.gnomish.app.port.tracker;

import com.github.oinsio.gnomish.DoNotMutate;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Abort history for a task, reconstructable by any instance from the tracker
 * alone (NFR-R3): {@code count} is the number of abort markers recorded
 * strictly after the latest durable-progress marker for the current claim —
 * markers at or before that progress marker are not counted — and {@code
 * lastAbortAt} is the timestamp of the most recent recorded abort. Adapters
 * report these facts as observed from structural markers; they never apply
 * backoff or the K-fuse policy themselves — that is core's job over
 * adapter-provided facts (design D10, FR14).
 *
 * <p>{@code count} zero means no aborts are on record, in which case {@code
 * lastAbortAt} is {@code null}; a positive {@code count} SHOULD carry a non-null
 * {@code lastAbortAt}, but that pairing is an adapter responsibility, not
 * enforced here — this value type only guards against a structurally
 * impossible negative count.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR14 of add-tracker-port; the reconstruction rule is FR3 of
 * fix-abort-progress-reset.
 *
 * @param count aborts recorded strictly after the latest durable-progress
 *     marker for the current claim; never negative
 * @param lastAbortAt when the most recent abort was recorded, or {@code null}
 *     if {@code count} is zero
 */
public record AbortFacts(int count, @Nullable Instant lastAbortAt) {

    public AbortFacts {
        requireNonNegative(count);
    }

    // PIT M4 documented exception (build.gradle has the full rationale): @DoNotMutate because
    // this method's only content is a `new AbortFacts(...)` record-construction call, the same
    // RUN_ERROR-triggering bytecode shape (hcoles/pitest#1285, JVMTI RedefineClasses restriction
    // on JDK 17+) as ExecutorUsage.none() and friends — not a real coverage gap. AbortFactsSpec's
    // "none() yields ..." scenario covers it at the ordinary test level.
    /** No aborts recorded — the initial state of a freshly ready task. */
    @DoNotMutate
    public static AbortFacts none() {
        return new AbortFacts(0, null);
    }

    // PIT M4 documented exception (build.gradle has the full rationale): @DoNotMutate because
    // this method crashes PIT's minion JVM (RUN_ERROR, not a real test gap) for the same
    // record-adjacent-private-method reason as ExecutorUsage's compact-constructor helpers —
    // it is invoked only from the compact constructor above, which PIT's record filter already
    // exempts from mutation, so the crash is purely a JVMTI redefinition artifact. Covered by
    // AbortFactsSpec's negative-count rejection scenario.
    /**
     * Fails fast on a negative {@code count}: an abort tally cannot be negative
     * (FR14). Kept as an explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt this validation from the
     * 100% mutation gate.
     */
    @DoNotMutate
    private static void requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("AbortFacts.count must not be negative");
        }
    }
}
