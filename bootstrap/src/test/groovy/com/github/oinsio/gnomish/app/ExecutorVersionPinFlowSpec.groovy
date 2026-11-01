package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path

/**
 * The describe preflight on the real host take path over a real bare origin (FR4, FR13, UX1 of
 * define-executor-contract; design D5, D11): the version the preflight obtained reaches the round
 * record through the engine, a later describe answering another version escalates {@code
 * PipelineMismatch} before any round — straight after a recorded round and across a park and a
 * human return — and a program that cannot be described parks the task as an invalid law does.
 *
 * <p>The executor is the stand-in preset {@code describe-as}, its link named after the describe
 * answer it gives; a round that runs is the {@code run-as} preset's, through the {@code program}
 * dispatch (task 7.3 of define-executor-contract).
 */
class ExecutorVersionPinFlowSpec extends TakeResumeSpecBase {

    private static final ObjectMapper JSON = new ObjectMapper()

    def "FR13: the version the preflight obtained rides the round the run records, under the executor's name"() {
        given: 'a task at its first stage, nothing pinned'
        start('PROJ-1', TaskState.atStageStart('build'))
        def runner = newTakeResumeRunner()

        when: 'the run describes acme at version 1 and runs one round of a manual stage'
        def result = runner.resumeWithoutDecision(
                resumeOrder(law(running('completed'), AdvancementMode.MANUAL)),
                runner.bootstrap(cloneDir, 'PROJ-1'), TaskState.atStageStart('build'))

        then: 'the round ran and the gate parked the task'
        result instanceof TakeResult.AwaitingHuman
        ((TakeResult.AwaitingHuman) result).reason() == ParkReason.CHECKPOINT

        and: 'the round commit carries the pin'
        branchJson('PROJ-1', 'state.json').get('executorVersions').get('acme').asText() == '1'
    }

    // Scenario: pipeline/stage-engine / Resume uses the recorded executor version
    def "FR13: a resume whose executor now describes version 2 escalates PipelineMismatch naming both versions, and no round runs"() {
        given: 'a task whose recorded round pinned acme at version 1'
        def pinned = recordedAt('1')
        start('PROJ-1', TaskState.atStageStart('build'))
        persistOneRound('PROJ-1', pinned)
        def roundsBefore = branchJson('PROJ-1', 'state.json').get('attempts').size()
        def runner = newTakeResumeRunner()

        when:
        def result = runner.resumeWithoutDecision(
                resumeOrder(law(describing('version-2'))), runner.bootstrap(cloneDir, 'PROJ-1'), pinned)

        then: 'the task parks on the mismatch'
        result instanceof TakeResult.AwaitingHuman
        mismatchOf('PROJ-1') == [executor: 'acme', recorded: '1', current: '2']

        and: 'no round ran'
        branchJson('PROJ-1', 'state.json').get('attempts').size() == roundsBefore
    }

    // Scenario: pipeline/stage-engine / The pin survives a park
    def "FR13: a task parked on an escalation and returned by a human still compares the pin, and no round runs"() {
        given: 'a task whose recorded round pinned acme at version 1, parked for a decision'
        def pinned = recordedAt('1')
        start('PROJ-1', TaskState.atStageStart('build'))
        persistOneRound('PROJ-1', pinned)
        def escalated = new TaskState(pinned.position(), 1, pinned.attempts(), pinned.totals(), pinned.executorVersions())
        repository().recordOutcome('PROJ-1', new TaskOutcome.Escalated(escalated, new EscalationReport.DecisionNeeded(
                        UntrustedText.agent('continue?'), [
                            UntrustedText.agent('yes'),
                            UntrustedText.agent('no')
                        ])), TrackerWrite.OWED)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, 'PROJ-1')

        when: 'the human answers, the attempts reset, and the executor now describes version 2'
        def decided = runner.appendDecision(bootstrap, escalated, escalated.resetAttempts(), 'go ahead')
        def result = runner.resumeDecided(
                resumeOrder(law(describing('version-2'))), bootstrap, decided, escalated.resetAttempts())

        then:
        result instanceof TakeResult.AwaitingHuman
        mismatchOf('PROJ-1') == [executor: 'acme', recorded: '1', current: '2']
        branchJson('PROJ-1', 'state.json').get('attempts').size() == 0
    }

    // Scenario: pipeline/pipeline-config / Describe that cannot run parks the task as a configuration error
    // Scenario: pipeline/pipeline-config / Describe that cannot start parks the task as an external failure
    // Scenario: executor/wire-contract / Describe output is untrusted
    // Scenario: executor/wire-contract / Version mismatch is refused before the first round
    // Scenario: executor/wire-contract / Undeclared role is refused before the first round
    // Scenario: pipeline/pipeline-config / Settings typo is caught before the first round
    def "FR4, UX1: a program that cannot be described parks the task as an invalid law does, naming the executor and the failure"() {
        given:
        start('PROJ-1', TaskState.atStageStart('build'))
        def runner = newTakeResumeRunner()
        def stateBefore = branchJson('PROJ-1', 'state.json')

        when:
        def result = runner.resumeWithoutDecision(
                resumeOrder(law(executor(program), AdvancementMode.AUTO, settings)), runner.bootstrap(cloneDir, 'PROJ-1'),
                TaskState.atStageStart('build'))

        then: 'the task parks INFRA with the located error, as an invalid law parks it, every byte of it inert'
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('an executor its pipeline law declares could not be described')
            && report.contains(failure)
            && !report.chars().anyMatch {
                it != ('\n' as char) && Character.isISOControl(it)
            }
        })
        result instanceof TakeResult.AwaitingHuman
        ((TakeResult.AwaitingHuman) result).reason() == ParkReason.INFRA

        and: 'no round ran and no attempt was burned'
        ((TakeResult.AwaitingHuman) result).finalState().attemptsUsed() == 0
        branchJson('PROJ-1', 'state.json') == stateBefore

        and: 'no abort was recorded'
        0 * tracker.recordAbort(*_)

        where:
        program | settings || failure
        'describe-failing' | [:] || "config.yaml: executors.acme.program: executor 'acme' cannot be described (configuration error): describe exited 3"
        'describe-oversized' | [:] || "config.yaml: executors.acme.program: executor 'acme' cannot be described (configuration error): the describe answer exceeds 65536 bytes"
        'control-chars' | [:] || "config.yaml: executors.acme.program: executor 'acme' cannot be described (configuration error): the describe answer carries a control character"
        'absent' | [:] || "config.yaml: executors.acme.program: executor 'acme' could not be described (external failure): the environment refused to start the program"
        'protocol-2' | [:] || "config.yaml: executors.acme: executor 'acme' speaks executor protocol version 2; this factory speaks version 1"
        'verdict-only' | [:] || "stages/build/stage.yaml: executor.name: executor 'acme' does not serve role 'transform', which stage 'build' needs; its describe lists verdict"
        'strict-settings' | [maxTurn: 3] || "config.yaml: executors.acme.settings.maxTurn: executor 'acme' settings break its schema's rule 'additionalProperties': property 'maxTurn' is not defined in the schema"
    }

    /** The program a row names: a stand-in preset, a describe answer, or a path nothing is at. */
    private Path executor(String name) {
        switch (name) {
            case 'absent': return Path.of('/nonexistent/gnomish-describe-absent')
            case 'control-chars':
            case 'protocol-2':
            case 'strict-settings':
                case 'verdict-only': return describing(name)
            default: return StandIn.executor(name)
        }
    }

    private void start(String taskId, TaskState state) {
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, resumableBaseRef()),
                TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), state)
    }

    private static TaskState recordedAt(String version) {
        new TaskState(TaskState.atStageStart('build').position(), 0, [], TaskState.atStageStart('build').totals(),
        new ExecutorVersions([acme: UntrustedText.subprocess(version)]))
    }

    /** The stand-in executor describing version 1 whose start answers the result {@code answer} names. */
    private Path running(String answer) {
        def dir = Files.createDirectories(tempDir.resolve("executor-run-${answer}"))
        StandIn.link(dir.resolve(answer), 'run-as')
    }

    /** The stand-in executor whose describe answers the section {@code answer} names. */
    private Path describing(String answer) {
        def dir = Files.createDirectories(tempDir.resolve("executor-${answer}"))
        StandIn.link(dir.resolve(answer), 'describe-as')
    }

    private static PipelineDefinition law(Path program, AdvancementMode mode = AdvancementMode.AUTO, Map<String, Object> settings = [:]) {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.PROGRAM, 'model-x', [:], Sandbox.none(), 'acme'),
        'instructions.md', [], new AutonomyLimits(3), mode)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage], null, [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, [program.toString()], settings, [], [])
        ])
    }

    private def branchJson(String taskId, String file) {
        JSON.readTree(gitOutput(cloneDir, 'show', "gnomish/${taskId}:.gnomish-task/${file}"))
    }

    private Map mismatchOf(String taskId) {
        def cause = branchJson(taskId, 'task.json').findValue('executorVersion')
        assert cause != null
        [executor: cause.get('executor').asText(), recorded: cause.get('recorded').asText(),
            current: cause.get('current').asText()]
    }
}
