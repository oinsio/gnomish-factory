package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The port through which the engine runs one round of a stage's work, without
 * knowing whether that work is an ai-provider API call or an agent CLI
 * subprocess (design D2): the {@link StageDefinition#executor()} mechanism is
 * the adapter's concern, the engine only asks for a round and reads back an
 * {@link ExecutionResult}.
 *
 * <p>The port names two operations (design D11 of extract-agent-round-runner, as amended
 * 2026-11-25). {@link #execute(Request)} starts a round: one call is one attempt, and the engine
 * drives retries by calling again with an incremented {@code attempt} and the accumulated {@code
 * feedback}. {@link #poll(Request, PendingTurn)} continues the round a {@code start} left {@code
 * pending}: a step inside that round, never a round of its own. A poll opens no round, mints no
 * round token, closes nothing on a {@code pending} answer and writes nothing on the task branch;
 * the round closes once, on the poll that answers a terminal status. There is no third way to
 * continue a round: the request carries no pending turn, so an implementation cannot treat a poll
 * as a start by not looking. A check round is the one subject whose poll is a request of its own
 * (a fresh environment per request, D14 of migrate-verify-to-executor-contract).
 *
 * <p>Implements FR1, D2 of add-stage-engine; FR10 of extract-agent-round-runner.
 */
public interface StageExecutor {

    /**
     * Runs one round of the stage described by {@code request} and returns its
     * outcome as a status plus an optional answer — {@code completed} when the
     * work finished (the engine proceeds to verify), {@code completed} with a
     * {@link ExecutionResult.DecisionRequest} when the executor hands control
     * back with a question (a gnome-initiated escalation, design D6), or a
     * {@code failed}/{@code pending} status (FR7 of define-executor-contract). The engine never inspects the request's workspace
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
     * Continues the round {@code request} names, left {@code pending} by its {@code start}, with
     * {@code turn}: the {@code poll} of the turn's key, in the round the {@code start} opened — or,
     * on resume, the one the run's record holds open. A poll opens no round, mints no token, writes
     * nothing on the task branch, and closes the round only when it answers a terminal status
     * (design D11 of extract-agent-round-runner, as amended 2026-11-25). The result has the shape
     * {@link #execute} returns.
     *
     * <p>Implements FR10, FR11 of extract-agent-round-runner.
     *
     * @param request the round's inputs, as its {@code start} had them; never null
     * @param turn the pending turn the poll continues: the key, and whether this run issued it or
     *     the record carried it; never null
     * @return the outcome of the poll; never null
     */
    ExecutionResult poll(Request request, PendingTurn turn);

    /**
     * The inputs of one execution round. Carries the task's {@link TaskContext}
     * (identity and human decisions, passed through verbatim), the
     * {@link StageDefinition} being run, the opaque {@link Workspace} the round
     * operates on, the zero-based {@code attempt} number, and the
     * {@code feedback} — the failed check results of <em>all</em> prior attempts
     * of this stage, so the executor can act on why earlier rounds were rejected.
     *
     * <p>The round's subject: a stage round has none beside its stage ({@code
     * check} is {@code null}, the role {@code transform}); a check round names the
     * check it judges, in role {@code verdict}, beside the stage it verifies,
     * with the check's identity in the round ({@link CheckRef}: the subject the
     * run's table of pending runs is keyed by) (design D14, as amended
     * 2026-11-13, D18 of migrate-verify-to-executor-contract). The check and
     * its identity are set together or not at all. The request carries no
     * pending turn: a continuation is {@link StageExecutor#poll}, which takes
     * the turn beside the request (D11 of extract-agent-round-runner, as amended
     * 2026-11-25).
     *
     * <p>The {@code feedback} list is defensively copied and unmodifiable and may
     * be empty (the first attempt has no prior feedback); its order is the order
     * supplied by the caller and is preserved faithfully. Inert value data
     * compared by content.
     *
     * <p>Implements FR1, D2 of add-stage-engine; FR1, FR2 of
     * migrate-verify-to-executor-contract.
     *
     * @param context the task's identity and human decisions; passed through
     *     verbatim
     * @param stage the stage definition being run this round
     * @param workspace the opaque working copy the round operates on
     * @param attempt the zero-based attempt number of this round; never negative
     * @param feedback the failed check results of all prior attempts;
     *     defensively copied, unmodifiable, possibly empty
     * @param check the check a check round judges, or {@code null} for a stage
     *     round
     * @param checkRef the check's identity in the round, set exactly when {@code
     *     check} is
     */
    record Request(
            TaskContext context,
            StageDefinition stage,
            Workspace workspace,
            int attempt,
            List<CheckResult> feedback,
            VerifyCheck.@Nullable Executor check,
            @Nullable CheckRef checkRef) {

        public Request {
            attempt = requireNonNegative(attempt, "attempt");
            feedback = List.copyOf(feedback);
            requireTogether(check, checkRef);
        }

        /**
         * A stage round: no check.
         *
         * @param context the task's identity and human decisions
         * @param stage the stage definition being run this round
         * @param workspace the opaque working copy the round operates on
         * @param attempt the zero-based attempt number of this round
         * @param feedback the failed check results of all prior attempts
         */
        public Request(
                TaskContext context,
                StageDefinition stage,
                Workspace workspace,
                int attempt,
                List<CheckResult> feedback) {
            this(context, stage, workspace, attempt, feedback, null, null);
        }

        /**
         * Refuses a check without its identity in the round, or an identity without its check
         * (design D14 as amended 2026-11-13): the subject a check round's pending runs are keyed by
         * exists exactly for a check round.
         *
         * <p>{@code @DoNotMutate}, the JVMTI redefinition reason of {@code testing.md}, as for {@link
         * #requireNonNegative}: PIT's "negated conditional" mutant here crashes its minion (RUN_ERROR,
         * hcoles/pitest#1285) on this record (task 4.3a of migrate-verify-to-executor-contract). Fully
         * covered by {@code StageExecutorRequestSpec} (either half alone refused, both and neither
         * accepted).
         */
        @DoNotMutate
        private static void requireTogether(VerifyCheck.@Nullable Executor check, @Nullable CheckRef checkRef) {
            if ((check == null) != (checkRef == null)) {
                throw new IllegalArgumentException(
                        "StageExecutor.Request.check and checkRef are set together or not at all");
            }
        }

        /**
         * Fails fast on a negative {@code attempt}: an attempt number cannot be
         * negative (FR1). Kept as an explicit static method rather than inline in
         * the compact constructor: PIT's record filter suppresses all mutations
         * inside a record's canonical constructor, which would silently exempt
         * this validation from the 100% mutation gate.
         *
         * <p>{@code @DoNotMutate}, the JVMTI redefinition reason of {@code testing.md}: since the
         * record gained its {@code @Nullable} {@code check} and {@code pending} components (task
         * 4.0b of migrate-verify-to-executor-contract), both of PIT's mutants here ("changed
         * conditional boundary", "negated conditional") crash their minion (RUN_ERROR,
         * hcoles/pitest#1285), reproduced in three runs. Fully covered by {@code
         * StageExecutorRequestSpec} (negative attempts refused, zero accepted).
         */
        @DoNotMutate
        private static int requireNonNegative(int value, String component) {
            if (value < 0) {
                throw new IllegalArgumentException("StageExecutor.Request." + component + " must not be negative");
            }
            return value;
        }
    }
}
