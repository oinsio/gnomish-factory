package com.github.oinsio.gnomish.adapter.agent.command

import com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor
import com.github.oinsio.gnomish.app.port.agent.RoundDecisionRequest
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ChildOutput
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The command built-in's {@code RoundExecutorFactory} (FR4, FR8, NG1 of add-command-executor; design
 * D2, D5, D6): the registry name {@code command}, the round's request built from the stage — the
 * declared command as the transform's instructions, the stage's settings as the request's — and the
 * built-in bound to the round's environment, run by the one shared round wrapper.
 */
@Timeout(60)
class CommandExecutorFactorySpec extends Specification {

    @TempDir
    Path workspace

    def factory = new CommandExecutorFactory(new VirtualClock())

    def "FR8: the built-in's registry name is command"() {
        expect:
        factory.name() == 'command'
        CommandExecutorFactory.NAME == 'command'
    }

    def "FR4: a round's request is a transform whose instructions are the declared command, with the stage's settings"() {
        when:
        def request = factory.request(CommandFixtures.round(CommandFixtures.stage([roundTimeout: 600], 'npm test')))

        then:
        request.role() == Role.TRANSFORM
        request.roleInputs().instructions() == 'npm test'
        request.workspace().path() == '.'
        request.policy() instanceof Policy.WorkspaceWrite
        request.settings().get('roundTimeout').asInt() == 600
        request.settings().size() == 1
        request.bounds() == Bounds.none()
        request.feedback().isEmpty()
        request.continuation() == null
    }

    def "FR4: a stage that declares no command has no command round to request"() {
        when:
        factory.request(CommandFixtures.round(CommandFixtures.stage([:], null)))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("stage 'build'")
    }

    def "FR4: the factory's executor runs in the environment it is handed"() {
        given:
        def environment = Mock(TaskExecutionEnvironment)
        def process = FakeProcess.running()
        process.exit(0)

        when:
        def result = factory.executor(environment).start(factory.request(CommandFixtures.round()))

        then:
        1 * environment.exec(_) >> Stub(ExecHandle) {
            output() >> ChildOutput.stdout(process)
            startedAt() >> Instant.EPOCH
            waitForExitOrTimeout(_, _) >> new ExecHandle.Wait.Exited(Duration.ZERO)
            waitForExit() >> 0
            postExitBound() >> Duration.ofSeconds(2)
        }
        result.status().wire() == 'completed'
    }

    // Scenario: executor/command-executor / A stray decision file changes nothing
    def "NG1: through the shared round wrapper, a decision file the round source finds is no escalation: Completed"() {
        given: 'a command that writes a decision request into the working copy and exits 0'
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            Files.writeString(builder.directory().toPath().resolve('decision-request.json'), '{"question":"ship?","options":["a","b"]}')
            def process = FakeProcess.running()
            process.exit(0)
            process
        } as ProcessLaunch
        def host = new HostTaskExecutionEnvironment(workspace, new VirtualClock(), ChildEnvAllowlist.none(), launch)
        host.materialize('gnomish/TASK-7', null)

        and: 'a round source that would even report a decision request at the close'
        def round = Stub(RoundEnvironmentSource.Round) {
            environment() >> host
            readDecision() >> Optional.of(new RoundDecisionRequest(UntrustedText.agent('ship?'),
                    [
                        UntrustedText.agent('a'),
                        UntrustedText.agent('b')
                    ]))
        }
        def rounds = Stub(RoundEnvironmentSource) {
            openRound(_) >> round
        }

        when:
        def result = new ProgramStageExecutor(factory, rounds).execute(CommandFixtures.round(CommandFixtures.stage(), workspace))

        then: 'classification follows the exit code only'
        result.status() instanceof ExecutionResult.Completed
        result.answer() == null

        cleanup:
        host.dispose()
    }
}
