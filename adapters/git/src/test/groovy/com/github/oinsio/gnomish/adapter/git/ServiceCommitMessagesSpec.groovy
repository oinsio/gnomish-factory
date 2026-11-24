package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR2 of add-git-workflow (design D14, proposal Q1): the machine-recognizable
 * commit-message scheme for service commits made by the git adapter — round,
 * task lifecycle event, salvage, cleanup. A human/audit aid, not a parser's
 * input (D14); this spec only pins down the exact text.
 */
class ServiceCommitMessagesSpec extends Specification {

    @Unroll
    def "FR2: round(#stage, #round) == #expected"() {
        expect:
        ServiceCommitMessages.round(RoundStates.roundOf(new AttemptKey('T-1', stage, round))) == expected

        where:
        stage | round | expected
        'build' | 3 | 'gnomish: round build#3'
        'verify' | 1 | 'gnomish: round verify#1'
        'plan' | 12 | 'gnomish: round plan#12'
    }

    @Unroll
    def "FR2: taskEvent(#event) == #expected"() {
        expect:
        ServiceCommitMessages.taskEvent(event) == expected

        where:
        event | expected
        TaskLifecycleEvent.STARTED | 'gnomish: task started'
        TaskLifecycleEvent.RESUMED | 'gnomish: task resumed'
        TaskLifecycleEvent.APPROVED | 'gnomish: task approved'
        TaskLifecycleEvent.COMPLETED | 'gnomish: task completed'
        TaskLifecycleEvent.PAUSED | 'gnomish: task paused'
        TaskLifecycleEvent.ESCALATED | 'gnomish: task escalated'
        TaskLifecycleEvent.ABORTED | 'gnomish: task aborted'
    }

    def "FR2: taskEvent is total over every TaskLifecycleEvent constant, with no gaps"() {
        expect:
        TaskLifecycleEvent.values().every { event ->
            def message = ServiceCommitMessages.taskEvent(event)
            message.startsWith('gnomish: task ') && message.length() > 'gnomish: task '.length()
        }
    }

    // FR15 of make-checkpoint-gate-durable (design D10): the one parsed subject names the round's
    // token after its stage and round, separated by one space — SnapshotTipCheck reads it back.
    def "FR15: snapshot(stage, round, token) names the round token after stage#round"() {
        expect:
        ServiceCommitMessages.snapshot('work', 0, RoundToken.of('0a1b2c')) == 'gnomish: snapshot work#0 0a1b2c'
    }

    // FR19 of migrate-verify-to-executor-contract (design D22): the unverified subject stays in the
    // service namespace and outside the snapshot prefix, so the recogniser never reads it
    def "FR19: unverifiedSnapshot(stage, round, token) is a service subject outside the snapshot prefix"() {
        when:
        def subject = ServiceCommitMessages.unverifiedSnapshot('work', 0, RoundToken.of('0a1b2c'))

        then:
        subject == 'gnomish: unverified-snapshot work#0 0a1b2c'
        subject.startsWith(GnomeRangeInspection.SERVICE_SUBJECT)
        !subject.startsWith(ServiceCommitMessages.SNAPSHOT_PREFIX)
    }

    // FR10 of extract-agent-round-runner (design D11): the pending-round snapshot stays in the service
    // namespace and outside both prefixes the recogniser reads, so it is never read as a round to verify
    def "FR10: pendingSnapshot(stage, round, token) is a service subject outside the snapshot and pending prefixes"() {
        when:
        def subject = ServiceCommitMessages.pendingSnapshot('work', 0, RoundToken.of('0a1b2c'))

        then:
        subject == 'gnomish: pending-snapshot work#0 0a1b2c'
        subject.startsWith(GnomeRangeInspection.SERVICE_SUBJECT)
        !subject.startsWith(ServiceCommitMessages.SNAPSHOT_PREFIX)
        !subject.startsWith(ServiceCommitMessages.PENDING_PREFIX)
    }

    // FR19 (design D22): the step's one choice of the subject by the round's close
    def "FR19: the snapshot step names a #close close #expected"() {
        expect:
        RoundSnapshot.subject(new AttemptKey('T-1', 'work', 2), RoundToken.of('0a1b2c'), close) == expected

        where:
        close || expected
        RoundClose.VERIFIABLE || 'gnomish: snapshot work#2 0a1b2c'
        RoundClose.UNVERIFIABLE || 'gnomish: unverified-snapshot work#2 0a1b2c'
        RoundClose.PENDING || 'gnomish: pending-snapshot work#2 0a1b2c'
    }

    // FR20 of migrate-verify-to-executor-contract (design D21): the stage from the position, round and
    // token from the list's last entry
    def "FR20: a pending commit's subject names the stage, the last entry's round and its token"() {
        given:
        def last = new PendingPollRecord(subject, 3, RoundToken.of('0a1b2c'), new PendingKey('run-1'), Instant.EPOCH)

        expect:
        ServiceCommitMessages.pending('work', last) == 'gnomish: pending work#3 0a1b2c'

        where: 'D11 of extract-agent-round-runner: the subject is the same for a check and for the round'
        subject << [
            new PendingSubject.Check(new CheckRef(1, UntrustedText.factory('ci'))),
            PendingSubject.ROUND
        ]
    }

    def "FR20: an entry of #named with no round token has no pending subject"() {
        when:
        ServiceCommitMessages.pending('work', new PendingPollRecord(subject, 3, null, new PendingKey('run-1'),
                Instant.EPOCH))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains('the entry of ' + named + ' carries none')

        where:
        subject | named
        new PendingSubject.Check(new CheckRef(4, UntrustedText.factory('ci'))) | 'check 4'
        PendingSubject.ROUND | 'the round'
    }

    def "FR2: salvage() is the fixed constant string"() {
        expect:
        ServiceCommitMessages.salvage() == 'gnomish: salvage'
    }

    def "FR2: cleanup() is the fixed constant string"() {
        expect:
        ServiceCommitMessages.cleanup() == 'gnomish: cleanup'
    }

    // FR10 of add-claim-heartbeat: the callers' specs compare the tip's subject with this method,
    // so only a literal here pins the message itself
    def "FR10: trackerWriteConfirmed() is the fixed constant string"() {
        expect:
        ServiceCommitMessages.trackerWriteConfirmed() == 'gnomish: task write-confirmed'
    }
}
