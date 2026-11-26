package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.PendingRoundKills.atPoll

import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The identity spec of a pending {@code transform} round resumed by a second instance (FR11, NFR-R3,
 * NFR-R4 of extract-agent-round-runner; design D11, "Resume"; crash-consistency item 14), on a real bare
 * origin, on the host and in container mode: a first instance runs the round's {@code start}, which
 * answers {@code pending}, lands the pending round commit and polls once, and dies before its next poll;
 * a second instance reads the register through the production recogniser, restores the round cell at its
 * assembly ({@link ExecutorAdapterSelector#stageRounds}) and runs the engine from the registered state.
 * The round it closes is the round that went pending: one token on every commit and on the record, the
 * participants of the one executor, and the ledger lines naming one round (window K2); the fixture is
 * {@link PendingRoundResumeSpecBase}.
 */
class PendingRoundIdentitySpec extends PendingRoundResumeSpecBase {

    def setup() {
        script([pendingAnswer()] * 2 << completedAnswer())
    }

    // Scenario: lifecycle/task-branch-contract / A pending round is resumed by the same owner
    // Scenario: git/git-task-persistence / A pending commit above a pending-round snapshot is a pending round
    // Scenario: pipeline/stage-engine / Kill after the pending commit resumes the poll
    // Scenario: pipeline/stage-engine / Resumed round keeps its identity
    def "FR11, NFR-R4: in #mode mode, a pending round closed by a second instance is the round that went pending"() {
        given: 'the first instance went pending, polled once and died'
        def first = new RecordingEventListener()
        def killed = instance(mode, first, null, 'box-a', atPoll(2))
        def token = RoundToken.of(created)

        expect: 'it died at its second poll; the register names its pending round commit'
        killed instanceof TaskOutcome.Escalated
        subjectsSince(created) == [
            ServiceCommitMessages.pendingSnapshot('build', 0, token),
            ServiceCommitMessages.pending('build', 0, token)
        ]
        gitOutput(clone, 'rev-parse', REGISTER) == tip()

        when: 'a second instance picks the task up through the production recogniser'
        def pending = recognise(mode)
        def second = new RecordingEventListener()
        def outcome = instance(mode, second, pending, 'box-b')

        then: 'the recogniser read a pending round of the round\'s token, above its pending-round snapshot'
        pending.close() == RoundClose.PENDING
        pending.token() == token

        and: 'the second instance made no start: its one request was a poll of the recorded key'
        requests() == [
            'start -',
            'poll ' + KEY,
            'poll ' + KEY
        ]

        and: 'no verify check ran before the round closed: the branch holds the round\'s four commits, one token'
        outcome instanceof TaskOutcome.Completed
        subjectsSince(created) == [
            ServiceCommitMessages.pendingSnapshot('build', 0, token),
            ServiceCommitMessages.pending('build', 0, token),
            ServiceCommitMessages.snapshot('build', 0, token),
            'gnomish: round build#0'
        ]
        gitOutput(clone, 'rev-parse', REGISTER) == tip()

        and: 'the closed attempt carries the token the round went pending with, verified once, no pending entry left'
        def closing = stateAt("refs/heads/${BRANCH}")
        closing.pendingPolls().isEmpty()
        def record = closing.attempts().last()
        record.roundToken() == token
        record.result() == AttemptRecord.Result.PASSED
        record.checkResults().size() == 1

        and: 'every participant of the record is the one executor the round went pending on, in its transform role'
        def wentPending = finished(first)
        def closed = finished(second)
        record.usage()*.executor().toSet() == [
            wentPending.usage().executor()
        ] as Set
        record.usage()*.role().toSet() == [wentPending.usage().role()] as Set

        and: 'the ledger lines name one round: its key and executor, pending with the key it is resumed by'
        closed.key() == wentPending.key()
        closed.executor() == wentPending.executor()
        closed.resumedKey() == UntrustedText.subprocess(KEY)
        closed.pendingKey() == null
        first.events.findAll {
            it instanceof EngineEvent.PendingPolled
        }*.key().toSet() == [wentPending.key()] as Set
        second.events.findAll {
            it instanceof EngineEvent.PendingPolled
        }*.turn() == [PendingTurn.Origin.RECORDED]

        and:
        secondPassIsNoOp(mode)

        where:
        mode << ['host', 'container']
    }
}
