package com.github.oinsio.gnomish.app.port.tracker;

import com.github.oinsio.gnomish.DoNotMutate;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The unified automatic-retry accounting for a task, reconstructable by any instance from the
 * tracker alone (NFR-R3): {@code count} is the number of abort markers recorded strictly after the
 * latest durable-progress marker on the task — markers at or before that progress marker are not
 * counted — and {@code lastAbortAt} is the timestamp of the most recent recorded one. Adapters
 * report these facts as observed from structural markers; they never apply backoff or the K-fuse
 * policy themselves — that is core's job over adapter-provided facts (design D10, FR14).
 *
 * <p>One counter, two categories (design D9 of harden-task-branch-contract): {@code count} covers
 * both {@link RecoveryCause} categories, so the threshold and the backoff are computed over the
 * total and there is no second fuse for automatic branch recovery to drift away from. {@code
 * recoveryCount} is the {@link RecoveryCause#RECOVERY_FAILURE} share of it and {@link #crashCount()}
 * the rest, so a quarantine report can name the two causes distinctly (NFR-O2). Quality attempts —
 * stage verification failures — are a separate count and never appear here.
 *
 * <p>{@code count} zero means nothing is on record, in which case {@code lastAbortAt} is {@code
 * null}; a positive {@code count} SHOULD carry a non-null {@code lastAbortAt}, but that pairing is
 * an adapter responsibility, not enforced here — this value type only guards against a
 * structurally impossible negative count or a recovery share larger than the total.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR14 of add-tracker-port; the reconstruction rule is FR3 of
 * fix-abort-progress-reset; the categorization is FR14 of harden-task-branch-contract.
 *
 * @param count attempts recorded strictly after the latest durable-progress marker on the task,
 *     both categories together; never negative
 * @param lastAbortAt when the most recent one was recorded, or {@code null} if {@code count} is
 *     zero
 * @param recoveryCount how many of {@code count} were {@link RecoveryCause#RECOVERY_FAILURE};
 *     never negative and never greater than {@code count}
 */
public record AbortFacts(int count, @Nullable Instant lastAbortAt, int recoveryCount) {

    public AbortFacts {
        requireNonNegative(count);
        requireShareOfTotal(recoveryCount, count);
    }

    /**
     * The pre-categorization shape, kept so an adapter or caller with no category to report reads
     * as the category every uncategorized marker meant: {@link RecoveryCause#INSTANCE_CRASH}, the
     * standalone crash fuse's only writer.
     *
     * @param count attempts on record; never negative
     * @param lastAbortAt when the most recent one was recorded, or {@code null}
     */
    public AbortFacts(int count, @Nullable Instant lastAbortAt) {
        this(count, lastAbortAt, 0);
    }

    // PIT documented exception (`.claude/rules/testing.md`, "JVMTI redefinition limit"):
    // @DoNotMutate because PrimitiveReturnsMutator on this method crashes PIT's minion JVM (RUN_ERROR with zero tests
    // run, not a real test gap) — the JVMTI RedefineClasses restriction on a record's own methods
    // (hcoles/pitest#1285, JDK 17+). Its sibling MathMutator on the same line is killed normally,
    // and AbortFactsSpec's "splits the one counter into the recovery share and the crash remainder"
    // scenario covers the method at the ordinary test level.
    /**
     * The {@link RecoveryCause#INSTANCE_CRASH} share of {@link #count()} — the rest of the total.
     *
     * @return crash-category attempts on record; never negative
     */
    @DoNotMutate
    public int crashCount() {
        return count - recoveryCount;
    }

    // PIT M4 documented exception (build.gradle has the full rationale): @DoNotMutate because
    // this method's only content is a `new AbortFacts(...)` record-construction call, the same
    // RUN_ERROR-triggering bytecode shape (hcoles/pitest#1285, JVMTI RedefineClasses restriction
    // on JDK 17+) as ExecutorUsage.none() and friends — not a real coverage gap. AbortFactsSpec's
    // "none() yields ..." scenario covers it at the ordinary test level.
    /** No attempts recorded — the initial state of a freshly ready task. */
    @DoNotMutate
    public static AbortFacts none() {
        return new AbortFacts(0, null, 0);
    }

    // PIT M4 documented exception (build.gradle has the full rationale): @DoNotMutate because
    // this method crashes PIT's minion JVM (RUN_ERROR, not a real test gap) for the same
    // record-adjacent-private-method reason as ExecutorUsage's compact-constructor helpers —
    // it is invoked only from the compact constructor above, which PIT's record filter already
    // exempts from mutation, so the crash is purely a JVMTI redefinition artifact. Covered by
    // AbortFactsSpec's negative-count rejection scenario.
    /**
     * Fails fast on a negative {@code count}: an attempt tally cannot be negative
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

    // PIT M4 documented exception: @DoNotMutate for the same compact-constructor-adjacent reason
    // as requireNonNegative above. Covered by AbortFactsSpec's recovery-share rejection scenarios.
    /**
     * Fails fast on a recovery share that is negative or exceeds the total: the categories partition
     * one counter, so a share outside {@code [0, count]} names no possible history (FR14 of
     * harden-task-branch-contract).
     */
    @DoNotMutate
    private static void requireShareOfTotal(int recoveryCount, int count) {
        if (recoveryCount < 0 || recoveryCount > count) {
            throw new IllegalArgumentException("AbortFacts.recoveryCount must be within [0, count]");
        }
    }
}
