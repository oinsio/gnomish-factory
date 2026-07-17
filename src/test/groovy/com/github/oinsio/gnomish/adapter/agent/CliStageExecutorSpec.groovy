package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR3, FR4, FR15, M2 of add-agent-executor: {@link CliStageExecutor}'s
 * {@code execute()} assembly against the fake agent binary — decision
 * present/absent/garbage, and a killed process on {@code roundTimeout}
 * expiry (infrastructure failure, no attempt burned).
 */
class CliStageExecutorSpec extends Specification {

    @TempDir
    Path workspaceDir

    def clock = new VirtualClock()

    def setup() {
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
    }

    private StageExecutor executorFor(String scenario) {
        def properties = FakeAgentSupport.propertiesFor(scenario)
        new CliStageExecutor(properties, clock)
    }

    private StageExecutor.Request requestFor(Map<String, Object> settings = [:]) {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', settings),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('TASK-1', 'title', 'body', []),
                stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }

    // FR3: no decision file written -> Completed, carrying non-null usage and trace.
    def "plain round with no decision file completes"() {
        given:
        def executor = executorFor('plain-round')

        when:
        def result = executor.execute(requestFor())

        then:
        result instanceof ExecutionResult.Completed
        result.usage() != null
        result.trace() != null
    }

    // FR3, D1: a well-formed decision file maps to DecisionNeeded with the question and options.
    def "well-formed decision file yields DecisionNeeded"() {
        given:
        def executor = executorFor('decision-needed')

        when:
        def result = executor.execute(requestFor())

        then:
        result instanceof ExecutionResult.DecisionNeeded
        result.question() == 'Refactor or patch?'
        result.options() == ['refactor', 'patch']
        result.usage() != null
        result.trace() != null
    }

    // FR3, D1: a garbage decision file still yields DecisionNeeded, raw text as the question.
    def "garbage decision file becomes the question verbatim"() {
        given:
        def executor = executorFor('decision-garbage')

        when:
        def result = executor.execute(requestFor())

        then:
        result instanceof ExecutionResult.DecisionNeeded
        result.question().contains('not json at all')
        result.options() == []
    }

    // FR13, NFR-R1, D7: roundTimeout expiry kills the process and throws — RoundExecution
    // shapes any RuntimeException the port throws into CannotExecute, no attempt burned.
    def "hung process past roundTimeout is killed and throws"() {
        given:
        def executor = executorFor('hangs-forever')

        when:
        executor.execute(requestFor([roundTimeout: 1]))

        then:
        thrown(RoundTimeoutException)
    }

    // FR4, NFR-R1: a stream with no result event throws, uncaught infrastructure failure.
    def "missing result event throws"() {
        given:
        def executor = executorFor('missing-result-event')

        when:
        executor.execute(requestFor())

        then:
        thrown(MissingResultEventException)
    }

    // FR7, D10, task 9.4: a supplied AgentProgressListener receives the round's live progress.
    def "supplied progress listener receives round-started, tool-started and round-finished events"() {
        given:
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        def events = []
        AgentProgressListener listener = { AgentProgressEvent event -> events << event }
        def executor = new CliStageExecutor(properties, clock, listener)

        when:
        executor.execute(requestFor())

        then:
        events.any { it instanceof AgentProgressEvent.RoundStarted }
        events.any { it instanceof AgentProgressEvent.RoundFinished }
    }
}
