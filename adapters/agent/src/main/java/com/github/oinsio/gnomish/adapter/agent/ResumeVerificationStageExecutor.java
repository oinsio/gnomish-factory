package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import java.time.Duration;
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
 * stage and round therefore skips the agent entirely: it records the pending
 * attempt commit and returns {@link ExecutionResult.Completed} with empty
 * telemetry (the original round's trace died with the unrecorded state
 * commit), so the engine proceeds straight to verification of exactly the
 * harvested attempt commit — no agent re-run, no attempt burned. Every other
 * request delegates unchanged; the engine stays untouched (D15).
 *
 * <p>Implements FR21 of add-sandbox-core.
 */
public final class ResumeVerificationStageExecutor implements StageExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResumeVerificationStageExecutor.class);

    private final StageExecutor delegate;
    private final AttemptCommitRef attemptCommit;

    private @Nullable PendingVerification pending;

    /**
     * @param delegate the real executor every non-matching round runs through; never null
     * @param attemptCommit the run's attempt-commit ref the pending snapshot is recorded into
     * @param pending the interrupted verification found at the branch tip, or null for none —
     *     consumed by the first matching round
     */
    public ResumeVerificationStageExecutor(
            StageExecutor delegate, AttemptCommitRef attemptCommit, @Nullable PendingVerification pending) {
        this.delegate = delegate;
        this.attemptCommit = attemptCommit;
        this.pending = pending;
    }

    @Override
    public ExecutionResult execute(Request request) {
        PendingVerification p = pending;
        if (p != null && p.stage().equals(request.stage().name()) && p.round() == request.attempt()) {
            pending = null;
            attemptCommit.record(p.attemptCommit());
            log.info(
                    "resuming interrupted verification of {} round {} at attempt commit {} — no agent re-run,"
                            + " no attempt burned (FR21)",
                    p.stage(),
                    p.round(),
                    p.attemptCommit());
            AttemptKey key =
                    new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
            return new ExecutionResult.Completed(
                    new ExecutorUsage(Duration.ZERO, List.of(), Map.of()), new ToolTrace(key, List.of()));
        }
        return delegate.execute(request);
    }
}
