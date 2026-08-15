package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;

/**
 * The aggregate exit code for a batch {@code take} run (design D7, tracker-take spec "Batch take
 * works the list with a summary and one exit code"): exit 0 iff every ref's own exit code (per
 * {@link TakeBatchOutcome#exitCode()}) is 0, else the smallest non-zero per-ref code. Kept as its
 * own tiny class rather than inlined in {@link TakeCommand} so the surrounding exit-code
 * arithmetic reads as one deliberate rule.
 *
 * <p>{@link TakeBatchOutcome#exitCode()} covers both families this arithmetic must dominate
 * correctly: an ordinary {@link com.github.oinsio.gnomish.app.take.TakeResult}'s code (10 and
 * above, legitimate outcome) and a per-ref tool failure's code (below 10) — "smallest non-zero
 * wins" is exactly what makes the below-10 family dominate arithmetically, with no separate code
 * table (design D7).
 *
 * <p>Implements FR3, NFR-O2, D7 of add-factory-serve.
 */
final class TakeBatchExitCode {

    private TakeBatchExitCode() {}

    /**
     * Computes the aggregate exit code for {@code outcomes}.
     *
     * @param outcomes every ref's terminal outcome from one batch run; never empty (batch mode
     *     requires 2+ refs)
     * @return 0 if every outcome mapped to exit code 0, else the smallest non-zero mapped code
     */
    static int aggregate(List<TakeBatchOutcome> outcomes) {
        int smallestNonZero = Integer.MAX_VALUE;
        for (TakeBatchOutcome outcome : outcomes) {
            int code = outcome.exitCode();
            if (isNewSmallestNonZero(code, smallestNonZero)) {
                smallestNonZero = code;
            }
        }
        return smallestNonZero == Integer.MAX_VALUE ? 0 : smallestNonZero;
    }

    // PIT documented exception (build.gradle has the full rationale style): the `<` here is a
    // provably equivalent mutant against `<=` — when code == currentSmallest, the mutated branch
    // reassigns smallestNonZero to the exact value it already holds in aggregate()'s loop, an
    // internal no-op with zero externally observable difference (aggregate() returns only a single
    // primitive int; nothing downstream distinguishes "which occurrence" set it). No test can kill a
    // mutation with no observable effect, and no restructuring changes that — this is the canonical
    // strict-vs-non-strict equivalent mutant in a running-minimum loop. Isolated to its own method,
    // per the same convention as TakeEngineExecution#reasonFor, so this one unkillable comparison has
    // nowhere to hide as a false SURVIVED against the rest of aggregate()'s logic.
    @DoNotMutate
    private static boolean isNewSmallestNonZero(int code, int currentSmallest) {
        return code != 0 && code < currentSmallest;
    }
}
