package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * {@code TaskState.pendingPolls}, the open round's list of pending polls (FR2, FR20 of
 * migrate-verify-to-executor-contract; design D21, the single-owner row
 * "{@code TaskState.withPendingPolls}"): {@code withPendingPolls} is its one writer and changes
 * nothing else, and every other writer of a {@code TaskState} — the round writers, the lifecycle
 * rewrites, the starting state and the short constructor — builds the state with an empty list, so
 * no entry outlives the round it belongs to.
 */
class TaskStatePendingPollsSpec extends Specification {

    static final RoundToken TOKEN = RoundToken.of('0123456789abcdef0123456789abcdef01234567')
    static final Instant SINCE = Instant.parse('2026-11-12T09:00:00Z')
    static final List<PendingPollRecord> POLLS = [
        poll(0, 'run-1'),
        poll(2, 'run-2')
    ]
    static final def DEFINITION = AdvancementSpec.pipeline(
    AdvancementSpec.stage('build', AdvancementMode.MANUAL, []),
    AdvancementSpec.stage('test', AdvancementMode.AUTO, []))

    static PendingPollRecord poll(int check, String key) {
        new PendingPollRecord(new CheckRef(check, UntrustedText.manifest('executor:scm-status:' + key)), 1, TOKEN,
                new PendingKey(key), SINCE)
    }

    static AttemptRecord round(AttemptRecord.Result result) {
        AttemptRecords.of(0, result, Instant.EPOCH, [], ExecutorUsage.none(), [], Stop.none())
    }

    /** A state of the open round with two pending polls, at {@code position}, after one {@code last} round. */
    static TaskState pendingAt(Position position, AttemptRecord.Result last) {
        new TaskState(position, 1, [round(last)], UsageSnapshot.EMPTY, ExecutorVersions.none()).withPendingPolls(POLLS)
    }

    // D21: the one writer sets the list and leaves position, attempts, counter and carried fields as they were
    def "withPendingPolls sets the open round's list and changes nothing else"() {
        given:
        def state = new TaskState(new Position.AtStage('build'), 1, [
            round(AttemptRecord.Result.QUALITY_FAILURE)
        ],
        UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def pending = state.withPendingPolls(POLLS)

        then:
        pending.pendingPolls() == POLLS
        pending.position() == state.position()
        pending.attemptsUsed() == state.attemptsUsed()
        pending.attempts() == state.attempts()
        pending.totals() == state.totals()
        pending.executorVersions() == state.executorVersions()

        and: 'the list is a copy the caller cannot change afterwards'
        def mutable = new ArrayList<PendingPollRecord>(POLLS)
        def copied = state.withPendingPolls(mutable)
        mutable.clear()
        copied.pendingPolls() == POLLS

        when: 'the list is replaced by an empty one'
        def cleared = pending.withPendingPolls([])

        then:
        cleared == state
    }

    // D21, the D9 row "TaskState.withPendingPolls": every other writer builds the state with an empty list
    def "#writer yields a state with no pending poll"() {
        expect:
        build().pendingPolls().isEmpty()

        where:
        writer | build
        'atStageStart' | { TaskState.atStageStart('build') }
        'the short constructor' | {
            new TaskState(new Position.AtStage('build'), 0, [], UsageSnapshot.EMPTY, ExecutorVersions.none())
        }
        'recordUnburnedRound' | {
            pendingAt(new Position.AtStage('build'), AttemptRecord.Result.QUALITY_FAILURE)
            .recordUnburnedRound(round(AttemptRecord.Result.CANNOT_VERIFY), ExecutorVersions.none())
        }
        'recordQualityFailure' | {
            pendingAt(new Position.AtStage('build'), AttemptRecord.Result.QUALITY_FAILURE)
            .recordQualityFailure(round(AttemptRecord.Result.QUALITY_FAILURE), ExecutorVersions.none())
        }
        'recordPassAndAdvance' | {
            pendingAt(new Position.AtStage('build'), AttemptRecord.Result.QUALITY_FAILURE)
            .recordPassAndAdvance(round(AttemptRecord.Result.PASSED), new Position.AtStage('test'),
            ExecutorVersions.none())
        }
        'advanceTo' | {
            pendingAt(new Position.AtStage('build'), AttemptRecord.Result.QUALITY_FAILURE)
            .advanceTo(new Position.AtStage('test'))
        }
        'startOfStage over a passed stage' | {
            pendingAt(new Position.AtStage('test'), AttemptRecord.Result.PASSED).startOfStage()
        }
        'resetAttempts' | {
            pendingAt(new Position.AtStage('build'), AttemptRecord.Result.DECISION_NEEDED).resetAttempts()
        }
        'approveGate' | {
            pendingAt(new Position.AwaitingApproval('build'), AttemptRecord.Result.PASSED).approveGate(DEFINITION)
        }
    }

    // D21: a startOfStage with nothing to normalize writes nothing — it returns the state it was given
    def "startOfStage with nothing to normalize returns the state unchanged, its list included"() {
        given:
        def state = pendingAt(new Position.AtStage('build'), AttemptRecord.Result.QUALITY_FAILURE)

        expect:
        state.startOfStage().is(state)
    }
}
