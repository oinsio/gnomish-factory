package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * What a {@code StageExecutor} returns for one executed round: either
 * {@link Completed} — the stage's work finished normally and the engine proceeds
 * to verify — or {@link DecisionNeeded} — the executor cannot proceed and asks a
 * human. The two sealed variants let the engine switch exhaustively while reading
 * the shared telemetry without a switch.
 *
 * <p>{@code DecisionNeeded} is a gnome-initiated escalation (design D6): the
 * executor hands control back with a question rather than a completion, and the
 * engine escalates it immediately <em>without</em> burning a stage attempt — an
 * unanswered question is not a quality failure. The engine never interprets the
 * question or its options; they are carried verbatim to the human.
 *
 * <p>Both variants carry the round's {@link ExecutorUsage} and its raw
 * {@link ToolTrace}, exposed through the interface accessors {@link #usage()} and
 * {@link #trace()} so the engine records the aggregate usage into the
 * {@code AttemptRecord} and correlates the trace by {@code AttemptKey} without
 * having to switch on the variant (design D5, two-level telemetry). Inert value
 * data compared by content.
 *
 * <p>Implements FR6, FR13 of add-stage-engine.
 */
public sealed interface ExecutionResult permits ExecutionResult.Completed, ExecutionResult.DecisionNeeded {

    /**
     * The round's executor telemetry, shared by both variants. The engine records
     * this aggregate usage into the {@code AttemptRecord} regardless of whether the
     * round completed or asked for a decision (design D5).
     *
     * <p>Implements FR13 of add-stage-engine.
     *
     * @return the round's executor usage; never null
     */
    ExecutorUsage usage();

    /**
     * The round's raw tool trace, shared by both variants. The engine correlates
     * this trace back to its attempt by the trace's {@code AttemptKey} header,
     * regardless of the variant (design D5).
     *
     * <p>Implements FR13 of add-stage-engine.
     *
     * @return the round's raw tool trace; never null
     */
    ToolTrace trace();

    /**
     * The executor finished the stage's work normally; the engine proceeds to
     * verify. Carries the round's telemetry — nothing more, since the outcome is
     * simply "done" (FR13).
     *
     * <p>Implements FR6 and carries the FR13 telemetry of add-stage-engine.
     *
     * @param usage the round's executor usage; never null
     * @param trace the round's raw tool trace; never null
     */
    record Completed(ExecutorUsage usage, ToolTrace trace) implements ExecutionResult {}

    /**
     * The executor cannot proceed and asks a human — a gnome-initiated escalation
     * (design D6). The engine escalates it immediately without burning a stage
     * attempt, and carries the {@code question} and {@code options} to the human
     * verbatim, never interpreting them.
     *
     * <p>The {@code options} list is defensively copied and unmodifiable and may be
     * empty — an open-ended question offers no fixed choices. Its entries are free
     * text carried as supplied. Also carries the round's telemetry (FR13).
     *
     * <p>Implements FR6 and carries the FR13 telemetry of add-stage-engine.
     *
     * @param question what the human must decide; never blank
     * @param options the candidate answers, free text; defensively copied,
     *     unmodifiable, possibly empty
     * @param usage the round's executor usage; never null
     * @param trace the round's raw tool trace; never null
     */
    record DecisionNeeded(String question, List<String> options, ExecutorUsage usage, ToolTrace trace)
            implements ExecutionResult {

        public DecisionNeeded {
            question = requireNonBlank(question, "question");
            options = List.copyOf(options);
        }

        /**
         * Fails fast on a blank {@code question}: a decision that asks the human
         * nothing cannot be answered (FR6). Kept as an explicit static method rather
         * than inline in the compact constructor: PIT's record filter suppresses all
         * mutations inside a record's canonical constructor, which would silently
         * exempt this validation from the 100% mutation gate.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        "ExecutionResult.DecisionNeeded." + component + " must not be blank");
            }
            return value;
        }
    }
}
