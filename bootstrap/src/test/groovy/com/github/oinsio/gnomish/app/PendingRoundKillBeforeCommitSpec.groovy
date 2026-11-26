package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.PendingRoundKills.atPendingWrite
import static com.github.oinsio.gnomish.app.PendingRoundKills.refusingRefsAt

import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * Kill window K1 of a pending {@code transform} round (NFR-R3, NFR-C2 of extract-agent-round-runner;
 * design D11 as amended 2026-11-25; crash-consistency item 10), on a real bare origin, on the host and in
 * container mode: the first instance's {@code start} answered {@code pending} and the instance died before
 * the pending round commit — either before the pending-round snapshot was registered, or with it
 * registered and nothing above it. A second instance re-drives {@code start} once, its ledger line names
 * the round it started rather than a resumed key, and a second recovery pass changes nothing. The fixture
 * is {@link PendingRoundResumeSpecBase}.
 */
class PendingRoundKillBeforeCommitSpec extends PendingRoundResumeSpecBase {

    static final String REDRIVEN = 'job-9'

    // Scenario: pipeline/stage-engine / Kill before the pending commit re-drives start
    def "NFR-R3, NFR-C2: in #mode mode, killed #window, a second instance re-drives start once and the ledger names it"() {
        given: 'the start went pending with a key the second instance never polls; the re-driven start names another'
        script([
            pendingAnswer(),
            pendingAnswer(REDRIVEN),
            completedAnswer()
        ])
        def kill = snapshotted ? atPendingWrite(1) : refusingRefsAt(tempDir.resolve('git-bin'), 'start', 1)
        def first = new RecordingEventListener()
        instance(mode, first, null, 'box-a', kill)

        expect: 'the register holds no pending round commit: the creation, or the pending-round snapshot alone'
        def registered = gitOutput(clone, 'rev-parse', REGISTER)
        subjectsSince(created) == (snapshotted ? [
            ServiceCommitMessages.pendingSnapshot('build', 0, RoundToken.of(created))
        ] : [])
        registered == tip()
        stateAt(REGISTER).pendingPolls().isEmpty()

        when: 'a second instance picks the task up through the production recogniser'
        def pending = recognise(mode)
        def second = new RecordingEventListener()
        def outcome = instance(mode, second, pending, 'box-b')

        then: 'nothing was handed back to verify; the round was started again, once, and polled by its new key'
        pending == null
        requests() == [
            'start -',
            'start -',
            'poll ' + REDRIVEN
        ]

        and: 'the ledger line of the round names the re-driven start: pending with its new key, resumed by none'
        def closed = finished(second)
        closed.resumedKey() == null
        closed.pendingKey() == UntrustedText.subprocess(REDRIVEN)

        and: 'the round closed on a round of its own, opened on the registered commit, no pending entry left'
        outcome instanceof TaskOutcome.Completed
        def token = RoundToken.of(registered)
        subjectsSince(registered).takeRight(4) == [
            ServiceCommitMessages.pendingSnapshot('build', 0, token),
            ServiceCommitMessages.pending('build', 0, token),
            ServiceCommitMessages.snapshot('build', 0, token),
            'gnomish: round build#0'
        ]
        def closing = stateAt(REGISTER)
        closing.pendingPolls().isEmpty()
        closing.attempts().last().roundToken() == token
        closing.attempts().last().result() == AttemptRecord.Result.PASSED
        gitOutput(clone, 'rev-parse', REGISTER) == tip()
        originTip() == tip()

        and:
        secondPassIsNoOp(mode)

        where:
        mode | snapshotted | window
        'host' | false | 'before the pending-round snapshot'
        'container' | false | 'before the pending-round snapshot'
        'host' | true | 'with the pending-round snapshot registered'
        'container' | true | 'with the pending-round snapshot registered'
    }
}
