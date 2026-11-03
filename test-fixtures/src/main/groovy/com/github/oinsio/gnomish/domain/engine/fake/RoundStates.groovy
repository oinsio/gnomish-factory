package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.time.Instant

/**
 * The state a round writer hands {@code AttemptPersistence.persist}: the stage's start with the one
 * round it records — an unburned {@code CANNOT_VERIFY} round with no checks, no usage and no stop,
 * under the identity of the round's {@link AttemptKey} (task 5.1 of define-executor-contract). A
 * persistence spec whose subject is the commit, not the state's content, records this round: the
 * port's state always ends with the round it records, and the round commit's subject is rendered
 * from it, so a round-less state is a shape no production writer hands over.
 *
 * <p>Test fixture, not production code.
 */
final class RoundStates {

    /** The instant every fixture round began. */
    static final Instant STARTED_AT = Instant.parse('2026-07-18T09:00:00Z')

    private RoundStates() {}

    /** The stage-start state of {@code key}'s stage, recording the round {@code key} names. */
    static TaskState recorded(AttemptKey key) {
        TaskState.atStageStart(key.stage()).recordUnburnedRound(roundOf(key), ExecutorVersions.none())
    }

    /** The state as {@link #recorded(AttemptKey)}, the key spelled out. */
    static TaskState recorded(String taskId, String stage, int round) {
        recorded(new AttemptKey(taskId, stage, round))
    }

    /**
     * {@code state} as a round writer hands it over for the round {@code key} names: unchanged when
     * it already records a round, otherwise with that round appended — for a fixture helper whose
     * callers pass a stage-start state as "any state".
     */
    static TaskState withRound(TaskState state, AttemptKey key) {
        state.attempts().isEmpty() ? state.recordUnburnedRound(roundOf(key), ExecutorVersions.none()) : state
    }

    /** The fixture round {@code key} names, carrying the fixture round token. */
    static AttemptRecord roundOf(AttemptKey key) {
        AttemptRecords.identified(key.taskId(), key.stage(), AttemptRecords.TOKEN, key.attempt(),
                AttemptRecord.Result.CANNOT_VERIFY, STARTED_AT, [], ExecutorUsage.none(), [],
                Stop.none())
    }
}
