package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.adapter.agent.program.AnswerOutcome;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.executorcontract.UsageProvenance;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link StageExecutor} decorator resuming an interrupted verification (FR21,
 * D15): when resume found a snapshot commit unrecorded in {@code state.json}
 * ({@link PendingVerification}), the factory died between
 * the snapshot and the state commit — the gnome's round completed, only its
 * verification was lost. The first {@link #execute} matching that snapshot's
 * stage and round therefore skips the agent entirely: it restores the interrupted
 * round into the run's {@link CurrentRound} — the token its snapshot recorded and the
 * snapshot itself, through the same two transitions a live round runs (design D10 of
 * make-checkpoint-gate-durable, as amended 2026-10-07) — and returns a {@link
 * ExecutionResult#completed} result with empty telemetry (the original round's trace died
 * with the unrecorded state commit), so the engine proceeds straight to verification of exactly the
 * harvested attempt commit — no agent re-run, no attempt burned. Every other
 * request delegates unchanged; the engine stays untouched (D15).
 *
 * <p>A round that wrote an answer file before the death left it in the snapshot's tree, and the
 * pending verification carries its reading, already read through the one {@code AnswerFileRead} the
 * live round reads its file through. The matching round's result is made from that reading by the
 * mapping the live agent round uses ({@link AnswerOutcome#ofAgent}) and mapped onto the engine's
 * shape by the one {@link ProgramResultMapping} every round goes through, with the same empty
 * telemetry: a decision request is re-raised as {@link ExecutionResult#decisionNeeded}, a file the
 * live round would have refused is the failure the live round would have had, and no answer
 * resumes as {@code Completed} — the round is not replayed, no attempt is burned (FR15, NFR-R4 of
 * make-checkpoint-gate-durable, design D10; FR21, NFR-R5 of extract-agent-round-runner, design
 * D15).
 *
 * <p>A registered pending commit above the snapshot (design D21 of
 * migrate-verify-to-executor-contract) resumes the same way: the restored round's checks judge the
 * snapshot, its close sits on the pending commit, and the round's pending entries reach the verify
 * chain on the state the pickup read at the register — this decorator stays the one owner that skips
 * the executor.
 *
 * <p>It wraps the stage chain of both branch modes (design D22 of
 * migrate-verify-to-executor-contract): the container's, and the git-mode host's over the run's one
 * cell, above the host snapshot step so a resumed round is not snapshotted again. A host snapshot
 * never carries a decision request (the host snapshots completed rounds only), so it re-verifies.
 *
 * <p>Implements FR21 of add-sandbox-core; FR13, FR15 of make-checkpoint-gate-durable;
 * FR3, FR19, FR20 of migrate-verify-to-executor-contract; FR21, NFR-R5 of extract-agent-round-runner.
 */
public final class ResumeVerificationStageExecutor implements StageExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResumeVerificationStageExecutor.class);

    /**
     * A recovered round's usage: zero wall time and nothing reported, since the original round's
     * telemetry died with the unrecorded state commit.
     */
    private static final Usage RECOVERED = new Usage(UsageProvenance.ABSENT, 0L, Map.of(), List.of());

    private final StageExecutor delegate;
    private final CurrentRound rounds;

    private @Nullable PendingVerification pending;

    /**
     * @param delegate the real executor every non-matching round runs through; never null
     * @param rounds the run's round cell the pending round is restored into
     * @param pending the interrupted verification found at the branch tip, or null for none —
     *     consumed by the first matching round
     */
    public ResumeVerificationStageExecutor(
            StageExecutor delegate, CurrentRound rounds, @Nullable PendingVerification pending) {
        this.delegate = delegate;
        this.rounds = rounds;
        this.pending = pending;
    }

    @Override
    public ExecutionResult execute(Request request) {
        PendingVerification p = pending;
        if (p != null && p.stage().equals(request.stage().name()) && p.round() == request.attempt()) {
            pending = null;
            rounds.restore(p);
            log.info(
                    "resuming interrupted verification of {} round {} at attempt commit {} — no agent re-run,"
                            + " no attempt burned (FR21)",
                    p.stage(),
                    p.round(),
                    p.attemptCommit());
            AttemptKey key =
                    new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
            // The live round's two steps, from the reading the snapshot's answer file gave: no
            // process is at hand, so no stderr tail; no denials either, for the same reason the
            // telemetry is empty: this round's environment is long gone, and its guard log died with
            // the unrecorded state commit.
            String executor = request.stage().executor().registryName();
            var recovered =
                    AnswerOutcome.ofAgent(Role.TRANSFORM, executor, p.answer(), UntrustedText.factory(""), RECOVERED);
            return ProgramResultMapping.toEngine(executor, Role.TRANSFORM, key, recovered, List.of());
        }
        return delegate.execute(request);
    }

    /**
     * A poll continues a round whose {@code start} already went through {@link #execute}, which is
     * where an interrupted verification is recognised: it is forwarded to the chain as it is (design
     * D11 of extract-agent-round-runner, as amended 2026-11-25).
     */
    @Override
    public ExecutionResult poll(Request request, PendingTurn turn) {
        return delegate.poll(request, turn);
    }
}
