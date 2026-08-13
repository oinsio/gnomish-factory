package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState

/**
 * FR9, FR12, D3 of add-tracker-port (task 5.6): {@link TakeResumeRunner#resumeWithDecision} —
 * resets {@code attemptsUsed} to 0 with an empty attempt history (mirroring {@code
 * EscalationResumeDialog#handleResumable}'s exact formula), appends the already-collected human
 * reply through {@code GitTaskRepository#appendDecision} (also resetting {@code outcome} to null
 * in the same durable write, FR12), then runs the engine exactly once with no console dialog.
 */
class TakeResumeRunnerWithDecisionSpec extends TakeResumeSpecBase {

    // FR12: the decision is appended via GitTaskRepository#appendDecision — durably recorded on
    // the branch, findable in a historical task.json blob (the terminal Completed commit's FR15
    // cleanup removes .gnomish-task/ from the tip, so history must be searched, mirroring
    // GitResumeOutcomeSpec's own escalation-resume assertion).
    def "resumeWithDecision appends the decision text durably via GitTaskRepository"() {
        given: 'a task escalated after one persisted round, needing a human decision'
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        runner.resumeWithDecision(
                cloneDir, bootstrap, pipeline(), escalatedState, 'go ahead',
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then: 'the answered decision text was committed via GitTaskRepository#appendDecision'
        def historicalTaskJsons = gitRunner.run(cloneDir, 'log', "gnomish/${taskId}", '--format=%H').stdout()
                .lines().collect {
                    gitRunner.run(cloneDir, 'show', "${it}:.gnomish-task/task.json")
                }
                .findAll { it.exitCode() == 0 }
                .collect { it.stdout() }
        historicalTaskJsons.any { it.contains('go ahead') }

        and: 'the decision is attributed to "tracker", not "operator" (design note: reply channel)'
        historicalTaskJsons.any { it.contains('"tracker"') }

        and: 'the decision records the AtStage position name, not a null stage'
        historicalTaskJsons.any { it.contains('"build"') }
    }

    // FR9, D3: the attempt counter is reset to 0 (with an empty attempt history) before the
    // engine resumes — the same formula EscalationResumeDialog#handleResumable applies for a live
    // manual-run resume. Proven indirectly: an attempt limit of 1 would otherwise immediately
    // re-escalate as AttemptsExhausted; instead the reset state lets the single-stage pipeline run
    // to completion.
    def "resumeWithDecision resets the attempt counter so a previously-exhausted stage can run again"() {
        given: 'a stage with attempt limit 1, already exhausted (attemptsUsed == 1) before resume'
        def taskId = 'PROJ-2'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def exhaustedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        def report = new EscalationReport.AttemptsExhausted(1)
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(exhaustedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        def limitOnePipeline = new com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition(
                '1', new com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits(1), [stage()])

        when:
        def result = runner.resumeWithDecision(
                cloneDir, bootstrap, limitOnePipeline, exhaustedState, 'try again',
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then: 'the run reached completion rather than re-escalating immediately as AttemptsExhausted'
        result instanceof TakeResult.Delivered
    }

    // FR9, FR12: an ESCALATION-park resume runs the engine exactly once (no dialog) and maps a
    // fresh Completed outcome to Delivered.
    def "resumeWithDecision runs the engine once and maps a Completed outcome to Delivered"() {
        given:
        def taskId = 'PROJ-3'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithDecision(
                cloneDir, bootstrap, pipeline(), escalatedState, 'go ahead',
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then:
        result instanceof TakeResult.Delivered
    }
}
