package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.take.TakeExitCodeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TakeResultDescription;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One ref's terminal outcome from a batch {@code take} run (task 6.2 of add-factory-serve): pairs
 * the raw ref string as given on the command line with the outcome its disposition produced, in
 * the same order {@link TakeArguments#refs()} listed them.
 *
 * <p>Exactly one of {@link #result} or {@link #toolFailure} is set. {@link TakeResult} is a closed
 * hierarchy (design D2/D3 of add-tracker-port) with no variant for "the tool itself could not
 * operate on this ref" (a below-10 {@link RunExitCodeMapper}-family failure, e.g. a per-ref {@link
 * UsageException} from an unresolvable short ref): every variant either requires a {@link
 * com.github.oinsio.gnomish.domain.engine.TaskState} the failure never reached, or — {@link
 * TakeResult.Skipped} — exists for genuine refusals and is deliberately mapped to the
 * legitimate-outcome family (exit 15), not the tool-failure family. Rather than widen {@link
 * TakeResult} for a case only batch mode needs, {@link #toolFailure} carries it at this,
 * batch-local level (task 6.3).
 *
 * <p>Implements FR3, NFR-O2, D7 of add-factory-serve.
 *
 * @param ref the raw ref string as given on the command line (not the resolved canonical {@link
 *     com.github.oinsio.gnomish.app.port.tracker.TaskRef}, since a refusal before resolution
 *     — e.g. an unresolvable short ref — still needs a ref to report against); never blank
 * @param result the terminal {@link TakeResult} this ref's disposition produced, or {@code null}
 *     iff {@link #toolFailure} is set
 * @param toolFailure the tool-could-not-operate failure this ref's disposition raised, or {@code
 *     null} iff {@link #result} is set
 */
record TakeBatchOutcome(
        String ref, @Nullable TakeResult result, @Nullable ToolFailure toolFailure) {

    TakeBatchOutcome {
        if ((result == null) == (toolFailure == null)) {
            throw new IllegalArgumentException("exactly one of result or toolFailure must be set");
        }
    }

    /** One ref's ordinary {@link TakeResult}; no tool failure occurred. */
    TakeBatchOutcome(String ref, TakeResult result) {
        this(ref, result, null);
    }

    /**
     * One ref whose disposition raised an uncaught {@link RuntimeException} instead of returning a
     * {@link TakeResult} — captured per-ref so the batch run continues past it (tracker-take spec
     * "Tool failure dominates").
     *
     * @param ref the raw ref string that failed; never blank
     * @param cause the uncaught exception the ref's disposition raised; never null
     */
    static TakeBatchOutcome toolFailure(String ref, RuntimeException cause) {
        return new TakeBatchOutcome(ref, null, ToolFailure.classify(cause));
    }

    // PIT M5 documented exception (build.gradle has the full rationale style): @DoNotMutate — this
    // instance method sits on a record, and its mutants hit the same known JVMTI RedefineClasses
    // limitation as the record-constructor helpers build.gradle already documents (hcoles/pitest#1285).
    // Fully covered by TakeBatchOutcomeSpec's exit-code scenarios for both the toolFailure and result
    // branches.
    /**
     * This outcome's exit code (design D7): {@link #toolFailure}'s below-10 code when set, else
     * {@code result}'s code per {@link TakeExitCodeMapper}.
     */
    @DoNotMutate
    int exitCode() {
        if (toolFailure != null) {
            return toolFailure.exitCode();
        }
        return TakeExitCodeMapper.exitCodeFor(Objects.requireNonNull(result));
    }

    // PIT M5 documented exception (build.gradle has the full rationale style): @DoNotMutate — same
    // record-method JVMTI limitation as exitCode() above. Fully covered by TakeBatchOutcomeSpec's
    // describe() scenarios for both the toolFailure and result branches.
    /** A short, grep-able description of this outcome, for the batch summary (NFR-O2, UX3). */
    @DoNotMutate
    String describe() {
        if (toolFailure != null) {
            return toolFailure.describe();
        }
        return TakeResultDescription.describe(Objects.requireNonNull(result));
    }

    /**
     * A per-ref tool failure, classified onto the same below-10 exit-code family {@code gnomish
     * run} uses for its own "tool could not operate" exceptions (design D7, {@link
     * RunExitCodeMapper}), so a batch's tool failures and a single-ref run's crash exit codes stay
     * numerically consistent.
     *
     * @param exitCode the below-10 exit code {@link RunExitCodeMapper} assigns {@code cause}'s type
     * @param message a short description of {@code cause}, for the batch summary
     */
    record ToolFailure(int exitCode, String message) {

        private static ToolFailure classify(RuntimeException cause) {
            String message = cause.getMessage() != null
                    ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            return new ToolFailure(new RunExitCodeMapper().getExitCode(cause), message);
        }

        private String describe() {
            return "tool failure (exit " + exitCode + "): " + message;
        }
    }
}
