package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration

/**
 * NFR-O1, UX1, UX3 of migrate-verify-to-executor-contract: what a pending check's wait reports to
 * the run's listener — one {@link EngineEvent.CheckPolled} per poll naming the executor, the key's
 * prefix, the turn, the elapsed and the remaining time; one {@link EngineEvent.CheckTimedOut} at the
 * timeout carrying the failure the wait was resolved to, whose details name what was still awaited
 * (the lines the logging listener renders from them are {@code LoggingEventListenerPollSpec}'s); and the
 * check's {@link CheckOutcome} on its finish event, which the ledger's {@code roundOutcome} line of
 * the check names: the executor, the status the rounds ended on, and the key a resume took up from
 * the pending record — never a key a poll rotated it to afterwards. On virtual time.
 */
class PendingPollObservabilitySpec extends PendingPollSpecBase {

    private List<EngineEvent.CheckPolled> polls() {
        events.events.findAll {
            it instanceof EngineEvent.CheckPolled
        } as List<EngineEvent.CheckPolled>
    }

    private List<EngineEvent.CheckTimedOut> timeouts() {
        events.events.findAll {
            it instanceof EngineEvent.CheckTimedOut
        } as List<EngineEvent.CheckTimedOut>
    }

    // NFR-O1: each poll reports executor, key prefix, the turn, elapsed and remaining time
    // Scenario: pipeline/stage-engine / Each poll of a pending check is one event
    def "NFR-O1: each poll of a started check reports its executor, key prefix, turn, elapsed and remaining time"() {
        given:
        def ci = check()
        def executor = new ScriptedExecutor([
            pending('k-1'),
            pending('k-1'),
            passed()
        ])

        when:
        poll(executor).answer(ci, ref(0, ci), round([ci]))

        then: 'one event per poll, the start not among them, the issued key shown as a subprocess capture'
        polls() == [
            new EngineEvent.CheckPolled(KEY, ref(0, ci), 'scm-status', UntrustedText.subprocess('k-1'),
            ISSUED, Duration.ofSeconds(30), Duration.ofSeconds(570)),
            new EngineEvent.CheckPolled(KEY, ref(0, ci), 'scm-status', UntrustedText.subprocess('k-1'),
            ISSUED, Duration.ofMinutes(1), Duration.ofMinutes(9))
        ]
        timeouts().isEmpty()
    }

    // NFR-O1: a started check that never went pending names its executor and its round's status
    def "NFR-O1: the outcome of a check that never went pending names its executor and its status, and no resume"() {
        given:
        def ci = check()

        when:
        def polled = poll(new ScriptedExecutor([passed()])).answer(ci, ref(0, ci), round([ci]))

        then:
        polled.outcome() == new CheckOutcome('scm-status', new ExecutionResult.Completed(), null)
        events.events.isEmpty()
    }

    // Scenario: verification/pending-poll / Kill after persist resumes the poll
    def "UX3: a resumed check's outcome names the recorded key it was taken up with, a later rotation notwithstanding"() {
        given: 'the record names the key a rotation before the kill persisted; the resumed poll rotates it again'
        def ci = check()
        def since = clock.instant().minus(Duration.ofMinutes(2))
        def state = STATE.withPendingPolls([
            entry(ref(0, ci), 'k-rotated', since)
        ])
        def executor = new ScriptedExecutor([pending('k-after'), passed()])

        when:
        def polled = poll(executor).answer(ci, ref(0, ci), round([ci], state))

        then: 'no start; the recorded key is polled first, and the outcome names it as read from the branch'
        turns(executor) == [
            [RECORDED, 'k-rotated'],
            [ISSUED, 'k-after']
        ]
        polled.outcome() == new CheckOutcome('scm-status', new ExecutionResult.Completed(),
                UntrustedText.branchDocument('k-rotated'))
        polls()[0] == new EngineEvent.CheckPolled(KEY, ref(0, ci), 'scm-status',
                UntrustedText.branchDocument('k-rotated'), RECORDED, Duration.ofMinutes(2), Duration.ofMinutes(8))
    }

    // Scenario: verification/pending-poll / Timeout names the missing contexts
    // Scenario: pipeline/stage-engine / A pending check's timeout is its last event
    def "UX1, NFR-O1: an scm-status check that times out names every expected context still absent in its finding and its timeout event"() {
        given:
        def ci = check()
        def absent = "waiting on 'lint', 'test' (absent)"
        def executor = new ScriptedExecutor([
            pending('k-1', null, PendingState.UNREPORTED, absent)
        ] * 21)

        when:
        def polled = poll(executor).answer(ci, ref(0, ci), round([ci]))

        then: 'the finding: a cannot-verify whose details name both contexts'
        def failure = polled.answer() as VerdictAnswer.InfrastructureFailure
        failure.details() == UntrustedText.manifest(absent)

        and: 'the timeout event carries the failure the engine resolved the wait to, naming both contexts'
        timeouts().size() == 1
        def timedOut = timeouts()[0]
        timedOut.key() == KEY
        timedOut.check() == ref(0, ci)
        timedOut.executor() == 'scm-status'
        timedOut.keyPrefix() == UntrustedText.subprocess('k-1')
        timedOut.elapsed() == Duration.ofMinutes(10)
        timedOut.failure().failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        timedOut.failure().details() == UntrustedText.manifest(absent)

        and: 'it is the last thing the wait reports: after the twentieth poll'
        events.events.last() instanceof EngineEvent.CheckTimedOut
        polls().size() == 20

        and: 'the outcome is the failure the engine resolved the wait to, never pending'
        (polled.outcome().status() as ExecutionResult.Failed).failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
    }

    // NFR-O1: a timeout whose wait had no reason says so
    def "NFR-O1: a timed-out quality wait with no reason reports its class and no awaited text"() {
        given:
        def ci = check()

        when:
        def polled = poll(new ScriptedExecutor([pending('k-1')] * 21)).answer(ci, ref(0, ci), round([ci]))

        then:
        timeouts()*.failure() == [polled.outcome().status()]
        timeouts()[0].failure().details() == null
        polled.outcome().status() == new ExecutionResult.Failed(ExecutionResult.FailureClass.QUALITY,
                UntrustedText.manifest("external check 'ci' did not complete within PT10M"),
                [
                    new Finding("external check 'ci' did not complete within PT10M", null, null)
                ], null)
    }

    // NFR-O1: an undelivered attempt is the infrastructure failure the engine resolved the check to
    def "NFR-O1: an undelivered attempt's outcome is failed/infrastructure with the delivery's words"() {
        given:
        def ci = check()
        def reason = UntrustedText.factory('push refused')
        def details = UntrustedText.subprocess('denied')
        def undeliverable = { w ->
            new AttemptDelivery.Outcome.Undeliverable(reason, details)
        } as AttemptDelivery

        when:
        def polled = poll(new ScriptedExecutor(), undeliverable).answer(ci, ref(0, ci), round([ci]))

        then:
        polled.outcome() == new CheckOutcome('scm-status',
                new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, reason, [], details), null)
        polled.answer() == new VerdictAnswer.InfrastructureFailure(reason, details)
    }

    // NFR-O1, UX3: the verify chain carries the outcome on the check's finish event; a judge has none
    def "NFR-O1, UX3: the check's finish event carries its outcome, an adapter's throw as failed/infrastructure"() {
        given:
        def ci = check()
        def listener = new RecordingEventListener()
        def throwing = new ScriptedExecutor()
        throwing.toThrow = new IllegalStateException('adapter broke')
        def chain = new VerifyOrchestrator(new PendingPoll(throwing, persistence, AttemptDelivery.assumedDelivered(),
                VirtualTimeEquipment.on(clock, sleeper), listener), new JudgeVoting(new ScriptedJudgeVoter()), clock, listener)

        when:
        chain.verify(VerifyingStages.of([ci]), CONTEXT, WORKSPACE, KEY, STATE, TOKEN)

        then:
        def finished = listener.events.find {
            it instanceof EngineEvent.CheckFinished
        } as EngineEvent.CheckFinished
        finished.outcome().executor() == 'scm-status'
        (finished.outcome().status() as ExecutionResult.Failed).failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        finished.outcome().resumedKey() == null
        finished.result().verdict() instanceof Verdict.CannotVerify
    }

    // NFR-O1: a judge runs no executor rounds, so its finish event carries no outcome
    def "NFR-O1: a judge check's finish event carries no outcome, even when its voter throws"() {
        given:
        def listener = new RecordingEventListener()
        def judge = new VerifyCheck.Judge('criteria.md', 'model', [:], 1)
        def chain = new VerifyOrchestrator(poll(new ScriptedExecutor()), new JudgeVoting(new ScriptedJudgeVoter()), clock, listener)

        when:
        chain.verify(VerifyingStages.of([judge]), CONTEXT, WORKSPACE, KEY, STATE, TOKEN)

        then:
        def finished = listener.events.find {
            it instanceof EngineEvent.CheckFinished
        } as EngineEvent.CheckFinished
        finished.outcome() == null
        finished.result().verdict() instanceof Verdict.CannotVerify
    }
}
