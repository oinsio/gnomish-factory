package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition

/**
 * FR9, FR12, D3 of add-tracker-port (task 5.6): {@link TakeResumeRunner#appendDecision} followed by
 * {@link TakeResumeRunner#resumeDecided} — the already-collected human reply is appended through
 * {@code GitTaskRepository#appendDecision} (resetting {@code outcome} to null in the same durable
 * write, FR12), the attempt counter is reset to 0 with an empty attempt history (mirroring {@code
 * EscalationResumeDialog#handleResumable}'s exact formula), and the engine then runs exactly once
 * with no console dialog. The two are separate calls because the tracker acknowledge belongs
 * between them (FR12 of harden-task-branch-contract).
 */
class TakeResumeRunnerWithDecisionSpec extends TakeResumeSpecBase {

    // FR12: the decision is appended via GitTaskRepository#appendDecision — durably recorded on
    // the branch, findable in a historical task.json blob (the terminal Completed commit's FR15
    // cleanup removes .gnomish-task/ from the tip, so history must be searched, mirroring
    // GitResumeOutcomeSpec's own escalation-resume assertion).
    def "an ESCALATION resume appends the decision text durably via GitTaskRepository"() {
        given: 'a task escalated after one persisted round, needing a human decision'
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def decided = runner.appendDecision(cloneDir, bootstrap, escalatedState, escalatedState.resetAttempts(), 'go ahead')
        runner.resumeDecided(
                cloneDir, bootstrap, pipeline(), decided, escalatedState.resetAttempts(),
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then: 'the answered decision text was committed via GitTaskRepository#appendDecision'
        def historicalTaskJsons = gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--format=%H')
                .lines()
                .findAll {
                    gitExitCode(cloneDir, 'show', "${it}:.gnomish-task/task.json") == 0
                }
                .collect {
                    gitOutput(cloneDir, 'show', "${it}:.gnomish-task/task.json")
                }
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
    def "an ESCALATION resume resets the attempt counter so a previously-exhausted stage can run again"() {
        given: 'a stage with attempt limit 1, already exhausted (attemptsUsed == 1) before resume'
        def taskId = 'PROJ-2'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def exhaustedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        def report = new EscalationReport.AttemptsExhausted(1)
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(exhaustedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)
        def limitOnePipeline = new PipelineDefinition('1', new AutonomyLimits(1), [stage()])

        when:
        def decided = runner.appendDecision(cloneDir, bootstrap, exhaustedState, exhaustedState.resetAttempts(), 'try again')
        def result = runner.resumeDecided(
                cloneDir, bootstrap, limitOnePipeline, decided, exhaustedState.resetAttempts(),
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then: 'the run reached completion rather than re-escalating immediately as AttemptsExhausted'
        result instanceof TakeResult.Delivered
    }

    // FR9, FR12: an ESCALATION-park resume runs the engine exactly once (no dialog) and maps a
    // fresh Completed outcome to Delivered.
    def "an ESCALATION resume runs the engine once and maps a Completed outcome to Delivered"() {
        given:
        def taskId = 'PROJ-3'
        repository().createTask(context(taskId), null, TaskState.atStageStart('build'))
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report))

        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def decided = runner.appendDecision(cloneDir, bootstrap, escalatedState, escalatedState.resetAttempts(), 'go ahead')
        def result = runner.resumeDecided(
                cloneDir, bootstrap, pipeline(), decided, escalatedState.resetAttempts(),
                RunArguments.InteractiveMode.ALL, tracker, REF, INSTANCE)

        then:
        result instanceof TakeResult.Delivered
    }
}
