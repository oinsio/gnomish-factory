package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.time.Instant

/**
 * FR12, FR13 of add-tracker-port (task 5.7): {@link TakeDecisionResume#resume} — collects
 * decisions at resume claim (FR12), acks the freshest pending reply before acting
 * (ack-before-acting, FR12), and re-parks a {@code DecisionNeeded} with no pending reply,
 * restating the question (FR13, design D12). {@code AttemptsExhausted} resumes on the return
 * alone when no reply is pending (design D12).
 */
class TakeDecisionResumeSpec extends TakeResumeSpecBase {

    private static HumanReply reply(String body, String isoInstant) {
        new HumanReply(body, Instant.parse(isoInstant))
    }

    // FR13, D12: a DecisionNeeded park with no pending reply re-parks, restating the question, and
    // no engine run happens.
    def "DecisionNeeded with empty replies re-parks restating the question, no engine run"() {
        given:
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        def decisionResume = new TakeDecisionResume(runner)
        tracker.collectDecisions(REF) >> []

        when:
        def result = decisionResume.resume(
                cloneDir, bootstrap, pipeline(), escalatedState,
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then: 'the question was restated in the park report'
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            it.contains('continue?')
        })
        0 * tracker.acknowledgeDecision(*_)

        and:
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.ESCALATION
        (result as TakeResult.AwaitingHuman).report().contains('continue?')
    }

    // FR12: a single pending reply on a DecisionNeeded park is acked BEFORE the engine resumes
    // (ack-before-acting), then the engine resumes with the decision appended.
    def "DecisionNeeded with one pending reply acks before acting, then resumes"() {
        given:
        def taskId = 'PROJ-2'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        def decisionResume = new TakeDecisionResume(runner)
        tracker.collectDecisions(REF) >> [
            reply('go ahead', '2026-07-18T09:00:00Z')
        ]
        def callOrder = []
        tracker.acknowledgeDecision(REF, 'go ahead') >> { callOrder << 'ack' }
        // fetchTask is called by the engine's revocation/abort machinery once the run is under way,
        // so its first invocation after resume() starts is a reliable "engine has begun" marker.
        tracker.fetchTask(_) >> {
            callOrder << 'engine-started'
            new com.github.oinsio.gnomish.app.port.tracker.TrackerTask(
                    REF, new com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot('PROJ-2', 'title', 'body'),
                    new com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState.Working(INSTANCE.value()),
                    com.github.oinsio.gnomish.app.port.tracker.AbortFacts.none())
        }

        when:
        def result = decisionResume.resume(
                cloneDir, bootstrap, pipeline(), escalatedState,
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then: 'ack happens before the engine resumes (no fetchTask call preceded it)'
        callOrder.indexOf('ack') == 0

        and:
        result instanceof TakeResult.Delivered
    }

    // FR12: with multiple pending replies, the LAST (freshest) one in posting order is the one
    // acted on and acknowledged.
    def "DecisionNeeded with multiple pending replies acts on the freshest one"() {
        given:
        def taskId = 'PROJ-3'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        def decisionResume = new TakeDecisionResume(runner)
        tracker.collectDecisions(REF) >> [
            reply('first reply', '2026-07-18T09:00:00Z'),
            reply('second reply', '2026-07-18T09:05:00Z'),
            reply('freshest reply', '2026-07-18T09:10:00Z')
        ]

        when:
        decisionResume.resume(
                cloneDir, bootstrap, pipeline(), escalatedState,
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then:
        1 * tracker.acknowledgeDecision(REF, 'freshest reply')
        0 * tracker.acknowledgeDecision(REF, 'first reply')
        0 * tracker.acknowledgeDecision(REF, 'second reply')
    }

    // D12: AttemptsExhausted with no pending reply still resumes the engine (the return itself is
    // confirmation) — no ack, no decision appended, attempt-counter reset applies.
    def "AttemptsExhausted with no pending reply resumes without ack, attempt counter reset applies"() {
        given: 'attempt limit 1, already exhausted before resume'
        def taskId = 'PROJ-4'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def exhaustedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        def report = new EscalationReport.AttemptsExhausted(1)
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(exhaustedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        def decisionResume = new TakeDecisionResume(runner)
        def limitOnePipeline = new com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition(
                '1', new com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits(1), [stage()])
        tracker.collectDecisions(REF) >> []

        when:
        def result = decisionResume.resume(
                cloneDir, bootstrap, limitOnePipeline, exhaustedState,
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then:
        0 * tracker.acknowledgeDecision(*_)
        0 * tracker.park(*_)
        result instanceof TakeResult.Delivered
    }

    // D12: AttemptsExhausted with a pending reply still acks and appends it — meaningful context
    // even though not required for resume.
    def "AttemptsExhausted with a pending reply acks and appends it, then resumes"() {
        given:
        def taskId = 'PROJ-5'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def exhaustedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        def report = new EscalationReport.AttemptsExhausted(1)
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(exhaustedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        def decisionResume = new TakeDecisionResume(runner)
        def limitOnePipeline = new com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition(
                '1', new com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits(1), [stage()])
        tracker.collectDecisions(REF) >> [
            reply('try again', '2026-07-18T09:00:00Z')
        ]

        when:
        def result = decisionResume.resume(
                cloneDir, bootstrap, limitOnePipeline, exhaustedState,
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then:
        1 * tracker.acknowledgeDecision(REF, 'try again')
        result instanceof TakeResult.Delivered

        and: 'the reply text was appended durably via GitTaskRepository#appendDecision'
        def historicalTaskJsons = gitRunner.run(cloneDir, 'log', "gnomish/${taskId}", '--format=%H').stdout()
                .lines().collect {
                    gitRunner.run(cloneDir, 'show', "${it}:.gnomish-task/task.json")
                }
                .findAll { it.exitCode() == 0 }
                .collect { it.stdout() }
        historicalTaskJsons.any { it.contains('try again') }
    }

    // Precondition guard: an INFRA-kind lastEscalation (CannotVerify) must never reach this
    // method — it is a caller error, not a case this class handles.
    def "an INFRA-kind lastEscalation throws IllegalStateException"() {
        given:
        def taskId = 'PROJ-6'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        def report = new EscalationReport.CannotVerify(
                new com.github.oinsio.gnomish.domain.engine.CheckRef(0, 'tests'), 'boom', '')
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        def decisionResume = new TakeDecisionResume(runner)
        tracker.collectDecisions(REF) >> []

        when:
        decisionResume.resume(
                cloneDir, bootstrap, pipeline(), escalatedState,
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then:
        thrown(IllegalStateException)
    }
}
