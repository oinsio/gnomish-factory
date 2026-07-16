package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.util.List;

/**
 * The port through which the engine runs one round of a stage's work, without
 * knowing whether that work is an ai-provider API call or an agent CLI
 * subprocess (design D2): the {@link StageDefinition#executor()} mechanism is
 * the adapter's concern, the engine only asks for a round and reads back an
 * {@link ExecutionResult}. One {@link #execute(Request)} call is one attempt;
 * the engine drives retries by calling again with an incremented
 * {@code attempt} and the accumulated {@code feedback}.
 *
 * <p>Implements FR1, D2 of add-stage-engine.
 */
public interface StageExecutor {

    /**
     * Runs one round of the stage described by {@code request} and returns its
     * outcome — {@link ExecutionResult.Completed} when the work finished (the
     * engine proceeds to verify) or {@link ExecutionResult.DecisionNeeded} when
     * the executor hands control back with a question (a gnome-initiated
     * escalation, design D6). The engine never inspects the request's workspace
     * or the round's mechanism; both belong to the adapter.
     *
     * <p>Implements FR1, D2 of add-stage-engine.
     *
     * @param request the round's inputs: task context, stage, workspace,
     *     attempt number and prior-attempt feedback
     * @return the outcome of the round; never null
     */
    ExecutionResult execute(Request request);

    /**
     * The inputs of one execution round. Carries the task's {@link TaskContext}
     * (identity and human decisions, passed through verbatim), the
     * {@link StageDefinition} being run, the opaque {@link Workspace} the round
     * operates on, the zero-based {@code attempt} number, and the
     * {@code feedback} — the failed check results of <em>all</em> prior attempts
     * of this stage, so the executor can act on why earlier rounds were rejected.
     *
     * <p>The {@code feedback} list is defensively copied and unmodifiable and may
     * be empty (the first attempt has no prior feedback); its order is the order
     * supplied by the caller and is preserved faithfully. Inert value data
     * compared by content.
     *
     * <p>Implements FR1, D2 of add-stage-engine.
     *
     * @param context the task's identity and human decisions; passed through
     *     verbatim
     * @param stage the stage definition being run this round
     * @param workspace the opaque working copy the round operates on
     * @param attempt the zero-based attempt number of this round; never negative
     * @param feedback the failed check results of all prior attempts;
     *     defensively copied, unmodifiable, possibly empty
     */
    record Request(
            TaskContext context, StageDefinition stage, Workspace workspace, int attempt, List<CheckResult> feedback) {

        public Request {
            attempt = requireNonNegative(attempt, "attempt");
            feedback = List.copyOf(feedback);
        }

        /**
         * Fails fast on a negative {@code attempt}: an attempt number cannot be
         * negative (FR1). Kept as an explicit static method rather than inline in
         * the compact constructor: PIT's record filter suppresses all mutations
         * inside a record's canonical constructor, which would silently exempt
         * this validation from the 100% mutation gate.
         */
        private static int requireNonNegative(int value, String component) {
            if (value < 0) {
                throw new IllegalArgumentException("StageExecutor.Request." + component + " must not be negative");
            }
            return value;
        }
    }
}
