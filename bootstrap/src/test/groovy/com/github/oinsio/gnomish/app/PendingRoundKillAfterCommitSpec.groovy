package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.PendingRoundKills.atClose
import static com.github.oinsio.gnomish.app.PendingRoundKills.atPendingWrite
import static com.github.oinsio.gnomish.app.PendingRoundKills.refusingRefsAt

import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener

/**
 * Kill windows K3, K4 and K5 of a pending {@code transform} round (NFR-R3, NFR-C2 of
 * extract-agent-round-runner; design D11 as amended 2026-11-25; crash-consistency items 8, 10), on a real
 * bare origin, on the host and in container mode, each picked up by a second instance and followed by a
 * second recovery pass that changes nothing. K2 is {@link PendingRoundIdentitySpec}'s, K1 {@link
 * PendingRoundKillBeforeCommitSpec}'s. The fixture executor conforms to "A pending key stays pollable until
 * its run ends": a superseded or an ended key answers again. The fixture is {@link PendingRoundResumeSpecBase}.
 */
class PendingRoundKillAfterCommitSpec extends PendingRoundResumeSpecBase {

    static final String ROTATED = 'job-8'

    def "NFR-R3: in #mode mode, killed after a poll named a new key and before its commit (K3), the previous key is polled and nothing started"() {
        given: 'the first poll names a new key; the previous one, polled again, answers the run\'s end'
        script([
            pendingAnswer(),
            pendingAnswer(ROTATED),
            completedAnswer()
        ])
        instance(mode, new RecordingEventListener(), null, 'box-a', atPendingWrite(2))
        def pendingCommit = gitOutput(clone, 'rev-parse', REGISTER)

        expect: 'the register is the one pending round commit, carrying the previous key'
        stateAt(REGISTER).pendingPolls()*.key()*.value() == [KEY]

        when:
        def outcome = instance(mode, new RecordingEventListener(), recognise(mode), 'box-b')

        then: 'the second instance polled the recorded, superseded key and made no start'
        requests() == [
            'start -',
            'poll ' + KEY,
            'poll ' + KEY
        ]
        closedOn(pendingCommit, outcome)

        and:
        secondPassIsNoOp(mode)

        where:
        mode << ['host', 'container']
    }

    // Scenario: pipeline/stage-engine / Kill after the terminal poll closes the round without a start
    // Scenario: pipeline/stage-engine / Second recovery of a pending round is a no-op
    def "NFR-R3: in #mode mode, killed after the terminal poll and before its snapshot (K4), the key is polled again and the round closes on its answer"() {
        given: 'the first poll answers the run\'s end; the snapshot that answer would register lands nothing'
        script([
            pendingAnswer(),
            completedAnswer(),
            completedAnswer()
        ])
        instance(mode, new RecordingEventListener(), null, 'box-a', refusingRefsAt(tempDir.resolve('git-bin'), 'poll', 1))
        def pendingCommit = gitOutput(clone, 'rev-parse', REGISTER)

        expect: 'the terminal poll was asked, and the register is still the pending round commit, its entry present'
        requests() == ['start -', 'poll ' + KEY]
        subjectsSince(created) == [
            ServiceCommitMessages.pendingSnapshot('build', 0, RoundToken.of(created)),
            ServiceCommitMessages.pending('build', 0, RoundToken.of(created))
        ]
        pendingCommit == tip()
        stateAt(REGISTER).pendingPolls()*.key()*.value() == [KEY]

        when:
        def outcome = instance(mode, new RecordingEventListener(), recognise(mode), 'box-b')

        then: 'the second instance polled the recorded key again, made no start, and closed with the answer'
        requests() == [
            'start -',
            'poll ' + KEY,
            'poll ' + KEY
        ]
        closedOn(pendingCommit, outcome)

        and:
        secondPassIsNoOp(mode)

        where:
        mode << ['host', 'container']
    }

    // Scenario: pipeline/stage-engine / Kill after the terminal snapshot re-verifies without a poll
    // Scenario: lifecycle/task-branch-contract / A closing snapshot above pending round commits is an interrupted verification
    def "NFR-R3: in #mode mode, killed with the terminal snapshot registered and no round close (K5), the snapshot is re-verified with no poll and no start"() {
        given:
        script([
            pendingAnswer(),
            completedAnswer()
        ])
        instance(mode, new RecordingEventListener(), null, 'box-a', atClose())
        def token = RoundToken.of(created)
        def pendingCommit = tip() + '~1'

        expect: 'the register is the round\'s snapshot on its pending round commit, its state still carrying the entry'
        subjectsSince(created) == [
            ServiceCommitMessages.pendingSnapshot('build', 0, token),
            ServiceCommitMessages.pending('build', 0, token),
            ServiceCommitMessages.snapshot('build', 0, token)
        ]
        gitOutput(clone, 'rev-parse', REGISTER) == tip()
        with(stateAt(REGISTER).pendingPolls()) {
            size() == 1
            it[0].subject() == PendingSubject.ROUND
            it[0].token() == token
        }

        when:
        def pending = recognise(mode)
        def second = new RecordingEventListener()
        def outcome = instance(mode, second, pending, 'box-b')

        then: 'the recogniser handed back the snapshot to verify, and the executor was asked nothing more'
        pending.close() == RoundClose.VERIFIABLE
        pending.token() == token
        requests() == ['start -', 'poll ' + KEY]
        second.events.findAll {
            it instanceof EngineEvent.PendingPolled
        }.isEmpty()
        closedOn(gitOutput(clone, 'rev-parse', pendingCommit), outcome)

        and:
        secondPassIsNoOp(mode)

        where:
        mode << ['host', 'container']
    }

    /**
     * The round closed verified on one snapshot above {@code pendingCommit}, under the token it went pending
     * with, and its closing commit — registered, at origin — carries no pending entry.
     */
    private void closedOn(String pendingCommit, TaskOutcome outcome) {
        def token = RoundToken.of(created)
        assert outcome instanceof TaskOutcome.Completed
        assert subjectsSince(pendingCommit) == [
            ServiceCommitMessages.snapshot('build', 0, token),
            'gnomish: round build#0'
        ]
        def closing = stateAt(REGISTER)
        assert closing.pendingPolls().isEmpty()
        assert closing.attempts().last().roundToken() == token
        assert closing.attempts().last().result() == AttemptRecord.Result.PASSED
        assert closing.attempts().last().checkResults().size() == 1
        assert gitOutput(clone, 'rev-parse', REGISTER) == tip()
        assert originTip() == tip()
    }
}
