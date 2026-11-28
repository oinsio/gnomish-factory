package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * The wait of a pending {@code transform} round (FR10, FR11, NFR-O2 of extract-agent-round-runner;
 * design D11), on virtual time: polls paced at {@code max(interval, pollAfter)}, the ceiling counted
 * from the recorded {@code since} live and resumed, its expiry {@code failed/infrastructure} naming it,
 * a refusal for an executor declaring no {@code poll}, and a rotated key rewritten before the next poll.
 */
class PendingRoundWaitSpec extends PendingTransformRoundSpecBase {

    // FR10 (D11): the pace is max(pending interval, pollAfter); no wait bound travels on the request
    def "polls are paced at the larger of the pending interval and the executor's hint"() {
        given: 'a 1-minute interval; hints of 10 seconds, then 5 minutes'
        executor.scripted << pending('job-42', Duration.ofSeconds(10)) << pending('job-42', Duration.ofMinutes(5)) << completed()
        checks.scripted << new Verdict.Pass(null, [])

        when:
        run()

        then:
        sleeper.slept == [
            Duration.ofMinutes(1),
            Duration.ofMinutes(5)
        ]
    }

    // NFR-O2, UX3: each poll is reported naming the round, the executor, the key prefix and the times;
    //     the round's finish names the key it went pending with
    def "each poll is reported for the round, and the round's finish names its pending key"() {
        given:
        executor.scripted << pending('job-42') << completed()
        checks.scripted << new Verdict.Pass(null, [])

        when:
        run()

        then:
        def polls = listener.events.findAll {
            it instanceof EngineEvent.PendingPolled
        }
        polls == [
            new EngineEvent.PendingPolled(new AttemptKey('TASK-1', 'build', 0), PendingSubject.ROUND, 'remote',
            UntrustedText.subprocess('job-42'), PendingTurn.Origin.ISSUED, Duration.ofMinutes(1), Duration.ofMinutes(59))
        ]
        def finished = listener.events.find {
            it instanceof EngineEvent.ExecutionFinished
        } as EngineEvent.ExecutionFinished
        finished.status() instanceof ExecutionResult.Completed
        finished.pendingKey() == UntrustedText.subprocess('job-42')
        finished.resumedKey() == null
    }

    // FR10, NFR-O2: the wait ends at the ceiling as failed/infrastructure naming it; the round escalates
    //     cannot-execute with no record and no check run
    def "a round still pending at the ceiling escalates naming the pending ceiling"() {
        given: 'the executor answers pending with a 40-minute hint, twice'
        executor.scripted << pending('job-42', Duration.ofMinutes(40)) << pending('job-42', Duration.ofMinutes(40))
        def logs = LogCaptureSupport.attach(RoundFailures, Level.WARN)

        when:
        def outcome = run()

        then:
        outcome instanceof TaskOutcome.Escalated
        def report = outcome.report() as EscalationReport.CannotExecute
        report.cause().forLog().contains('pending ceiling')
        report.cause().forLog().contains(BOUNDS.ceiling().toString())
        persistence.entries.isEmpty()
        checks.calls.isEmpty()
        executor.requests.size() == 2
        clock.instant() == persistence.pending[0].state.pendingPolls()[0].since().plus(BOUNDS.ceiling())
        def timedOut = listener.events.find {
            it instanceof EngineEvent.PendingTimedOut
        } as EngineEvent.PendingTimedOut
        timedOut.subject() == PendingSubject.ROUND
        timedOut.failure().failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE

        and: 'one operator line: the reported-failure WARN naming the round and the ceiling'
        logs.list.size() == 1
        logs.list[0].formattedMessage.startsWith(OperatorEvent.EXECUTOR_REPORTED_FAILURE.head())
        logs.list[0].formattedMessage.contains('TASK-1')
        logs.list[0].formattedMessage.contains('pending ceiling')

        cleanup:
        logs?.detach()
    }

    // FR10: a stage whose executor declares no poll is not handed to the poll, and a check's pending
    //     is still the check's (the subject stays Check)
    def "a pending from an executor declaring no poll is neither written nor polled"() {
        given:
        executor.scripted << pending('job-42')

        when:
        def outcome = run([] as Set)

        then:
        outcome instanceof TaskOutcome.Escalated
        persistence.pending.isEmpty()
        executor.requests.size() == 1
        listener.events.every { !(it instanceof EngineEvent.PendingPolled) }
    }

    // FR10, FR11: a round entry read off the record continues with no start, its wait counted from the
    //     recorded since — the remaining wait is the ceiling minus the time since then, and expiry is
    //     failed/infrastructure naming the ceiling
    // Scenario: pipeline/stage-engine / Restart does not reset the pending ceiling
    def "a resumed round entry waits only what is left of the ceiling since its recorded start"() {
        given: 'an entry recorded 50 minutes ago; the executor still answers pending with a 20-minute hint'
        def since = clock.instant().minus(Duration.ofMinutes(50))
        def entry = new PendingPollRecord(PendingSubject.ROUND, 0, TOKEN, new PendingKey('job-42'), since)
        executor.scripted << pending('job-42', Duration.ofMinutes(20))
        def poll = new PendingPoll(executor, persistence, VirtualTimeEquipment.on(clock, sleeper), listener)
        def start = new StageExecutor.Request(CONTEXT, STAGE, new FakeWorkspace(), 0, [])
        def ask = new RoundAsk(start, new AttemptKey('TASK-1', 'build', 0), TaskState.atStageStart('build'), TOKEN,
                'remote', BOUNDS)

        when:
        def run = poll.resumeRound(ask, entry)

        then: 'polled at once with the recorded key and no start; ten minutes were left, not an hour'
        executor.turns == [
            new PendingTurn(new PendingKey('job-42'), PendingTurn.Origin.RECORDED)
        ]
        (listener.events.find {
            it instanceof EngineEvent.PendingPolled
        } as EngineEvent.PendingPolled).remaining() ==
        Duration.ofMinutes(10)
        sleeper.slept == [Duration.ofMinutes(10)]
        clock.instant() == since.plus(BOUNDS.ceiling())

        and: 'the expiry names the ceiling as an infrastructure failure, and the resume names the recorded key'
        def failed = run.result().status() as ExecutionResult.Failed
        failed.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        failed.reason().forLog().contains('pending ceiling ' + BOUNDS.ceiling())
        run.resumedKey() == UntrustedText.branchDocument('job-42')
        run.pendingKey() == null
        persistence.pending.isEmpty()
    }

    // FR12 reuse (D12 of migrate-verify-to-executor-contract): a rotated key of a round rewrites only the
    //     round's entry, since unchanged, before the next poll
    def "a poll naming another key rewrites the round entry before the next poll"() {
        given:
        executor.scripted << pending('job-42') << pending('job-43') << completed()
        checks.scripted << new Verdict.Pass(null, [])
        def since = clock.instant()

        when:
        run()

        then:
        persistence.pending*.state*.pendingPolls() == [
            [
                new PendingPollRecord(PendingSubject.ROUND, 0, TOKEN, new PendingKey('job-42'), since)
            ],
            [
                new PendingPollRecord(PendingSubject.ROUND, 0, TOKEN, new PendingKey('job-43'), since)
            ]
        ]
        executor.writesBefore == [0, 1, 2]
        executor.turns[2] == new PendingTurn(new PendingKey('job-43'), PendingTurn.Origin.ISSUED)
    }
}
