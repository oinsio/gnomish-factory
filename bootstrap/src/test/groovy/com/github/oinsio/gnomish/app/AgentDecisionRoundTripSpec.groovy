package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Agent-raised decision round-trip, task 9.5 (FR3, UX3, D1): a real {@code CliStageExecutor}
 * round against the fake agent binary writes a decision file; the engine turns the unanswered
 * {@code DecisionNeeded} into {@code TaskOutcome.Escalated}; {@link RunnerOutcomeLoop} renders it
 * as an escalation stop (UX3: "rendered exactly like engine escalations"; FR1 of
 * make-run-headless), and a second run resumes the same stage with a reset attempt counter and the
 * answer appended as a {@link Decision} (FR3, D1); the resumed attempt is a fresh {@code claude
 * -p} round whose prompt — built by {@code ExecutorPromptBuilder} from {@code
 * TaskContext.decisions()} — carries the operator's answer verbatim into the fake agent's actual
 * stdin prompt (prompts travel on stdin since add-sandbox-core, FR24/D18), captured via {@code
 * GNOMISH_FAKE_CAPTURE_STDIN} (see {@code fake-agent/README.md},
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
class AgentDecisionRoundTripSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path workspaceDir

    /** Where the agent stand-in's per-run link and its capture live — apart from the workspace. */
    @TempDir
    Path standInDir

    private static final String QUESTION = 'Refactor or patch?'
    private static final String ANSWER = 'Re: "Refactor or patch?" — refactor everything, do not patch'

    private FactoryProperties fakeAgentProperties(String scenario, Path stdinCapture) {
        testProperties(agentCliBinary: FakeAgentSupport.binaryCapturingStdin(scenario, stdinCapture))
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

    // FR3, UX3, D1: full round trip — the escalation stop's text, then a second run carrying the
    // operator's answer, and the resumed CLI invocation's actual stdin prompt carrying it verbatim.
    // FR1 of make-run-headless: the first run stops at the question instead of prompting for it;
    // the second run stands in for the resume, with the answer appended and attempts reset.
    def "an agent-raised decision stops the run as an escalation, and the answer resumes the stage with the decision verbatim in the CLI round's prompt"() {
        given: 'instructions.md the stage control file reads, and a captured-stdin file the fake will append to'
        Files.createDirectories(workspaceDir.resolve('.gnomish'))
        Files.writeString(workspaceDir.resolve('.gnomish/instructions.md'), 'Do the thing.')
        def captureFile = standInDir.resolve('decision-then-plain.log').toFile()

        and: 'no operator input at all: stdin is empty'
        def capturedOut = new ByteArrayOutputStream()
        def assembly = newAssembly(new ByteArrayInputStream(new byte[0]), new PrintStream(capturedOut, true, 'UTF-8'),
                fakeAgentProperties('decision-then-plain', captureFile.toPath()))

        def context = new TaskContext('task-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of())
        def initialState = TaskState.atStageStart('build')
        def persistence = new InMemoryAttemptPersistence()
        def run = assembly.assemble(new RunOrder(workspaceDir, null, pipeline(), false),
                context, initialState, persistence, [], LawBinding.workingTree(workspaceDir))

        when: 'the first run stops at the question'
        def stop = run.loop().run(pipeline(), context, initialState, new DirectoryWorkspace(workspaceDir), run.ports(), null)

        then: '1. the decision surfaced as an escalation stop, question and options rendered like an engine escalation'
        stop instanceof TaskOutcome.Escalated
        (stop as TaskOutcome.Escalated).report() instanceof EscalationReport.DecisionNeeded
        def printed = capturedOut.toString('UTF-8')
        printed.contains('The gnome asked:')
        printed.contains(QUESTION)
        printed.contains('refactor')
        printed.contains('patch')

        and: '2. nothing prompted for an answer'
        !printed.contains('Decision (empty to resume without one)')

        when: 'the run continues with the answer appended and attempts reset, as a decision resume does'
        def answered = new TaskContext(context.taskId(), context.title(), context.body(),
                [
                    new Decision(ANSWER, 'build', 'operator', Instant.parse('2026-10-06T10:00:00Z'))
                ])
        def resetState = stop.finalState().resetAttempts()
        def resumed = assembly.assemble(new RunOrder(workspaceDir, null, pipeline(), false),
                answered, resetState, persistence, [], LawBinding.workingTree(workspaceDir))
        def finished = resumed.loop().run(pipeline(), answered, resetState, new DirectoryWorkspace(workspaceDir), resumed.ports(), null)

        then: '3. the resumed round ran the same stage to a clean finish'
        finished instanceof TaskOutcome.Completed
        capturedOut.toString('UTF-8').contains('pipeline complete')

        and: '4. the resumed attempt (a fresh claude -p round) received the operator answer verbatim in its stdin prompt'
        def capturedInvocations = captureFile.text.split('(?m)^---$')
                .collect { it.trim() }
                .findAll { !it.isEmpty() }
        capturedInvocations.size() == 2

        and: 'attempt 1 (no decision yet made) never saw the operator answer text in its prompt'
        !capturedInvocations[0].contains(ANSWER)

        and: "attempt 2's prompt carries the operator's answer verbatim — question and all (FR3, D1: the decision, not a command)"
        def secondInvocation = capturedInvocations[1]
        secondInvocation.contains(ANSWER)
        secondInvocation.contains(QUESTION)
    }
}
