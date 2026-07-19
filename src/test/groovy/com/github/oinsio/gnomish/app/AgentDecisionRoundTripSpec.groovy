package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Agent-raised decision round-trip, task 9.5 (FR3, UX3, D1): a real {@code CliStageExecutor}
 * round against the fake agent binary writes a decision file; the engine turns the unanswered
 * {@code DecisionNeeded} into {@code TaskOutcome.Escalated}; {@link RunnerOutcomeLoop} renders it
 * as an escalation dialog (UX3: "rendered exactly like engine escalations"), accepts the
 * operator's scripted answer, and resumes the same stage with a reset attempt counter and the
 * answer appended as a {@link Decision} (FR3, D1); the resumed attempt is a fresh {@code claude
 * -p} round whose prompt — built by {@code ExecutorPromptBuilder} from {@code
 * TaskContext.decisions()} — carries the operator's answer verbatim into the fake agent's actual
 * CLI argv, captured via {@code GNOMISH_FAKE_CAPTURE_ARGV} (see {@code fake-agent/README.md},
 * task 9.5). Per {@code Decision}'s own contract (domain spec of add-stage-engine), only the
 * answer travels forward as recorded context — the question itself was already delivered to the
 * operator by the escalation dialog print, not re-carried into {@code TaskContext} — so this
 * spec's scripted operator answer names the question inline, the way a real reply naturally
 * would, making "the decision in the next round's prompt" checkable end to end.
 *
 * <p>Uses the {@code decision-then-plain} fake-agent scenario (multi-attempt, D1): attempt 1
 * plays {@code decision-needed}, attempt 2+ in the same workspace plays {@code plain-round} —
 * the stand-in for "the operator answered, so the next attempt is a normal completing round".
 */
class AgentDecisionRoundTripSpec extends Specification {

    @TempDir
    Path workspaceDir

    private static final String QUESTION = 'Refactor or patch?'
    private static final String ANSWER = 'Re: "Refactor or patch?" — refactor everything, do not patch'

    private FactoryProperties fakeAgentProperties(String scenario, String captureArgvPath) {
        URL resource = getClass().getResource('/fake-agent/fake-agent.sh')
        def scriptPath = Path.of(resource.toURI()).toAbsolutePath().toString()
        def wrapper = File.createTempFile('fake-agent-wrapper', '.sh')
        wrapper.text = """#!/bin/sh
export GNOMISH_FAKE_SCENARIO='${scenario}'
export GNOMISH_FAKE_CAPTURE_ARGV='${captureArgvPath}'
exec sh '${scriptPath}' "\$@"
"""
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        new FactoryProperties('test-instance', wrapper.absolutePath, [])
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    // FR3, UX3, D1: full round trip — escalation dialog text, accepted answer, resumed stage,
    // and the resumed CLI invocation's actual argv carrying the operator's answer verbatim.
    def "an agent-raised decision escalates as a dialog, the operator's answer resumes the stage, and the resumed CLI round's prompt carries the decision verbatim"() {
        given: 'instructions.md the stage control file reads, and a captured-argv file the fake will append to'
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
        def captureFile = File.createTempFile('fake-agent-argv', '.log')
        captureFile.deleteOnExit()

        and: 'operator input scripted with the escalation answer, then a bare Enter for the final Completed summary'
        def scriptedIn = new ByteArrayInputStream((ANSWER + System.lineSeparator()).getBytes('UTF-8'))
        def capturedOut = new ByteArrayOutputStream()
        def consoleIo = new SystemConsoleIO(scriptedIn, new PrintStream(capturedOut, true, 'UTF-8'))

        def assembly = new ManualRunAssembly(
                consoleIo,
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new SystemClock(),
                new ThreadSleeper(),
                fakeAgentProperties('decision-then-plain', captureFile.absolutePath))

        def context = new TaskContext('task-1', 'title', 'body', List.<Decision> of())
        def initialState = TaskState.atStageStart('build')
        def run = assembly.assemble(
                pipeline(), context, initialState, RunArguments.InteractiveMode.NONE, new InMemoryAttemptPersistence())

        when:
        run.loop().run(pipeline(), context, initialState, new DirectoryWorkspace(workspaceDir), run.ports())

        then: 'no exception — the run completed after the resumed round'
        noExceptionThrown()

        and: '1. attempt 1 surfaced the decision as an escalation dialog, question and options rendered exactly like an engine escalation'
        def printed = capturedOut.toString('UTF-8')
        printed.contains('The gnome asked: ' + QUESTION)
        printed.contains('refactor')
        printed.contains('patch')

        and: '2. the operator was prompted to answer, and the scripted answer was accepted (loop reached the resumed round without an EOF/error)'
        printed.contains('Decision (empty to resume without one): ')

        and: '3. the run resumed and reached Completed — proof the resumed round ran the same stage to a clean finish'
        printed.contains('pipeline complete')

        and: '4. the resumed attempt (attempt 2, a fresh claude -p round) actually received the operator answer verbatim in its prompt argv'
        def capturedInvocations = captureFile.text.split('(?m)^---$')
                .collect { it.trim() }
                .findAll { !it.isEmpty() }
        capturedInvocations.size() == 2

        and: 'attempt 1 (no decision yet made) never saw the operator answer text in its prompt'
        !capturedInvocations[0].contains(ANSWER)

        and: "attempt 2's prompt carries the operator's answer verbatim — question and all, since that is exactly what the operator typed (FR3, D1: the decision, not a command)"
        def secondInvocation = capturedInvocations[1]
        secondInvocation.contains(ANSWER)
        secondInvocation.contains(QUESTION)
    }
}
