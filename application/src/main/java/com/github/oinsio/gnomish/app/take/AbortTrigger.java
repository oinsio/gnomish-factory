package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause;
import org.jspecify.annotations.Nullable;

/**
 * What tripped one infrastructure abort, as one value: the {@link RecoveryCause category} of the
 * unified recovery accounting the attempt spends from, and the live exception when the trigger
 * still holds one. The two facts are only meaningful together — the ERROR log site branches on
 * whether a throwable exists and always renders the category — so they travel as one record rather
 * than two trailing parameters (design D8 of add-parameter-count-gate).
 *
 * <p>The two triggers differ in what they can give the log (design D7 of
 * harden-untrusted-text-sinks). An uncaught take-run exception is a live {@link Throwable}, so
 * Logback renders its stack and cause chain — indented, one frame per line. An engine {@code
 * Aborted} outcome carries only a string the domain already rendered, with no throwable anywhere
 * in reach, so that one has {@code crash == null} and the cause text is all there is. The two
 * factories are the only way to build a trigger, so no caller hands the nullable in by hand.
 *
 * <p>Implements FR6 of add-parameter-count-gate; FR14 of harden-task-branch-contract.
 *
 * @param category which category of the unified accounting this attempt spends — a crashed run or
 *     a failed branch repair; never null
 * @param crash the exception the abort came from, or {@code null} when the trigger is an engine
 *     {@code Aborted} outcome
 */
public record AbortTrigger(RecoveryCause category, @Nullable Throwable crash) {

    /**
     * An engine {@code Aborted} outcome — a durable persist failed inside a running round.
     *
     * <p>PIT documented exception ({@code .claude/rules/testing.md}, JVMTI redefinition limit):
     * {@code @DoNotMutate} because PIT's Gregor engine crashed its own minion JVM (RUN_ERROR with
     * zero tests run, not a test gap) on the null-return mutation of this explicit method inside a
     * record on JDK 17+ (hcoles/pitest#1285; the risk design D8 of add-parameter-count-gate names).
     * The method holds no decision. Its effect is covered by {@code AbortHandlerSpec},
     * {@code AbortCauseCapWiringSpec} and the {@code TakeOutcomeDispatch} dispatch specs, which
     * pass through it on every engine-aborted path.
     */
    @DoNotMutate
    public static AbortTrigger engineAborted(RecoveryCause category) {
        return new AbortTrigger(category, null);
    }

    /** An uncaught exception of the take run itself, categorized by the caller. */
    public static AbortTrigger crashed(RecoveryCause category, Throwable crash) {
        return new AbortTrigger(category, crash);
    }
}
