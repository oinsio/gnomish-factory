package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A verdict check's pending wait (design D2, D19, D21 of migrate-verify-to-executor-contract): the
 * check round's request in its {@link PendingRound}, the check's declared interval and timeout, its
 * declared timeout class, and the round's list of entries, which every pending write of the
 * verification carries whole.
 *
 * <p>Implements FR2, FR3, FR4, FR10 of migrate-verify-to-executor-contract; FR10 of
 * extract-agent-round-runner.
 *
 * <p>A final class rather than a record: PIT's engine crashes its minion on some mutations of a
 * record's methods (hcoles/pitest#1285), and every method here is in the mutation gate.
 */
final class CheckAsk implements PendingAsk {

    private final VerifyCheck.Executor check;
    private final CheckRef ref;
    private final PendingRound round;

    /**
     * @param check the executor check; never null
     * @param ref the check's identity in the round; never null
     * @param round the open round the check verifies; never null
     */
    CheckAsk(VerifyCheck.Executor check, CheckRef ref, PendingRound round) {
        this.check = check;
        this.ref = ref;
        this.round = round;
    }

    @Override
    public AttemptKey key() {
        return round.key();
    }

    @Override
    public @Nullable RoundToken token() {
        return round.token();
    }

    @Override
    public PendingSubject subject() {
        return new PendingSubject.Check(ref);
    }

    @Override
    public String executor() {
        return check.executorName();
    }

    @Override
    public StageExecutor.Request request() {
        return round.request(check, ref);
    }

    @Override
    public PendingWait waitFrom(TimeEquipment time, Instant since) {
        return PendingWait.ofCheck(time, check, since);
    }

    @Override
    public ExecutionResult.Failed timedOut(PendingTimeout timeout) {
        return timeout.failure(check);
    }

    @Override
    public TaskState withEntry(PendingPollRecord entry) {
        return round.withEntry(entry);
    }

    @Override
    public TaskState withRotated(PendingPollRecord rotated) {
        return round.withRotated(rotated);
    }
}
