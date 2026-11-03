package com.github.oinsio.gnomish.domain.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The identity a round's writer stamps on its {@link AttemptRecord} (FR14, FR21 of
 * define-executor-contract, design D13): the round's {@link AttemptKey} — task, stage, number — the
 * round token taken once from the run's round identity, the instant the round began, and the
 * {@code transform} entry of the executor that ran it (FR15 of define-executor-contract). The three
 * places that record a round ({@link RoundExecution}'s verified round, {@link RoundRecords}'
 * quality-failed and stopped rounds) build the record through {@link #record}, so none of them
 * spells the identity on its own.
 *
 * @param key the round's correlation key: its task, its stage and its number; never null
 * @param token the tip the round opened on, in a branch mode; {@code null} in the in-place run
 * @param startedAt the instant the round began (FR15 of add-manual-run); never null
 * @param executed the participant entry of the executor that ran the round; never null
 */
record RecordedRound(AttemptKey key, @Nullable RoundToken token, Instant startedAt, ParticipantUsage executed) {

    /**
     * The record of this round with the given outcome.
     *
     * @param result how the round ended; never null
     * @param checks the round's check results; possibly empty
     * @param votes the round's judge votes as {@code verdict} entries, in vote order; possibly empty
     * @param denials the round's egress denials; possibly empty
     * @param stop the stop the round raised; never null
     * @return the record carrying this identity and, as its usage, the executor's entry followed by
     *     the votes; never null
     */
    AttemptRecord record(
            AttemptRecord.Result result,
            List<CheckResult> checks,
            List<ParticipantUsage> votes,
            List<Denial> denials,
            Stop stop) {
        var usage = new ArrayList<ParticipantUsage>();
        usage.add(executed);
        usage.addAll(votes);
        return new AttemptRecord(
                key.taskId(), key.stage(), token, key.attempt(), result, startedAt, checks, usage, denials, stop);
    }
}
