package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A pending {@code transform} round's wait (design D11 of extract-agent-round-runner): the round's own
 * request with the turn it continues — its context, stage, working copy, attempt and feedback, no
 * check — the factory's {@link PendingBounds}, whose ceiling expires as {@code failed/infrastructure}
 * naming it, and the one entry the round's pending round commit carries.
 *
 * <p>The state a pending write carries is the last persisted state with its list replaced by the
 * round's one entry and nothing else changed: the position stays where it is and no attempt is
 * recorded, so a pending round commit closes no attempt and authorises no verification or
 * advancement (crash-consistency item 12 — the stop rides the record). An entry of an earlier round
 * that state may still name is never carried.
 *
 * <p>Implements FR10, FR11 of extract-agent-round-runner.
 *
 * <p>A final class rather than a record, as {@link CheckAsk} is (hcoles/pitest#1285).
 */
final class RoundAsk implements PendingAsk {

    private final StageExecutor.Request start;
    private final AttemptKey key;
    private final TaskState state;
    private final @Nullable RoundToken token;
    private final String executor;
    private final PendingBounds bounds;

    /**
     * @param start the round's {@code start} request; never null
     * @param key the round's correlation key; never null
     * @param state the last persisted state the round ran from; never null
     * @param token the round's token, or {@code null} in the in-place run
     * @param executor the executor the stage named, as its events and ledger line show it; never blank
     * @param bounds the factory's pending interval and ceiling; never null
     */
    RoundAsk(
            StageExecutor.Request start,
            AttemptKey key,
            TaskState state,
            @Nullable RoundToken token,
            String executor,
            PendingBounds bounds) {
        this.start = start;
        this.key = key;
        this.state = state;
        this.token = token;
        this.executor = executor;
        this.bounds = bounds;
    }

    @Override
    public AttemptKey key() {
        return key;
    }

    @Override
    public @Nullable RoundToken token() {
        return token;
    }

    @Override
    public String executor() {
        return executor;
    }

    @Override
    public PendingSubject subject() {
        return PendingSubject.ROUND;
    }

    @Override
    public StageExecutor.Request request(@Nullable PendingTurn turn) {
        return new StageExecutor.Request(
                start.context(), start.stage(), start.workspace(), start.attempt(), start.feedback(), turn);
    }

    @Override
    public PendingWait waitFrom(TimeEquipment time, Instant since) {
        return PendingWait.ofRound(time, bounds, since);
    }

    @Override
    public ExecutionResult.Failed timedOut(PendingTimeout timeout) {
        return timeout.ceiling(bounds.ceiling());
    }

    @Override
    public TaskState withEntry(PendingPollRecord entry) {
        return state.withPendingPolls(List.of(entry));
    }

    @Override
    public TaskState withRotated(PendingPollRecord rotated) {
        return state.withPendingPolls(List.of(rotated));
    }
}
