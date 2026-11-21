package com.github.oinsio.gnomish.adapter.agent.command

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The command round's bound (FR3, FR8 of add-command-executor; design D4): the stage's {@code
 * roundTimeout}, resolved with the factory's bounds by the one {@code RoundBounds} (FR7, D6 of
 * extract-agent-round-runner), is the bound the round's wait is handed,
 * and a start in a role the built-in does not describe runs nothing. How each outcome is reported is
 * {@code CommandExitCodeSpec}'s.
 *
 * <p>Pinned on a {@link CannedCommandHandle}, which records the timeout it was handed, so no real time
 * passes and no order is left to the scheduler.
 */
@Timeout(30)
class CommandRoundBoundSpec extends Specification {

    def factory = new CommandExecutorFactory(new VirtualClock(), ProgramBounds.DEFAULTS)

    /** One start of a round of a stage with {@code settings}, whose command exits 0. */
    private Map start(Map<String, Object> settings) {
        def handle = new CannedCommandHandle(0, new ExecHandle.Wait.Exited(Duration.ofSeconds(4)))
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }
        def result = factory.executor(environment).start(factory.request(CommandFixtures.round(CommandFixtures.stage(settings))))
        [result: result, timeout: handle.timeoutSeen]
    }

    // Scenario: executor/command-executor / Well-formed roundTimeout is accepted
    def "FR3: a declared roundTimeout of #declared is the bound the round's wait is handed"() {
        when:
        def run = start([roundTimeout: declared])

        then:
        run.timeout == bound
        run.result.status() == Status.COMPLETED

        where:
        declared || bound
        600 || Duration.ofSeconds(600)
        'PT10M' || Duration.ofMinutes(10)
    }

    // Scenario: executor/command-executor / Default timeout applies
    def "FR3: a stage declaring no roundTimeout is bounded by the factory's 30-minute overall bound"() {
        expect:
        start([:]).timeout == ProgramBounds.DEFAULTS.overall()
        ProgramBounds.DEFAULTS.overall() == Duration.ofMinutes(30)
    }

    // FR7 of extract-agent-round-runner: the factory's overall bound is the one the factory was
    //     built with, never a constant of the built-in's own
    def "FR7: a stage declaring no roundTimeout is bounded by the overall bound of the factory's bounds it was built with"() {
        given:
        def bounds = new ProgramBounds(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(77))
        def handle = new CannedCommandHandle(0, new ExecHandle.Wait.Exited(Duration.ofSeconds(4)))
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }
        def built = new CommandExecutorFactory(new VirtualClock(), bounds)

        when:
        built.executor(environment).start(built.request(CommandFixtures.round(CommandFixtures.stage([:]))))

        then:
        handle.timeoutSeen == Duration.ofSeconds(77)
    }

    def "FR8: a start in a role the describe does not list runs nothing and is failed/infrastructure"() {
        given:
        def environment = Mock(TaskExecutionEnvironment)
        def request = new ExecutorRequest(Role.VERDICT, new Workspace('.'), new Policy.WorkspaceWrite(),
                RoleInputs.verdict('is it good?'), JsonNodeFactory.instance.objectNode(), Bounds.none(), [], null)

        when:
        def result = factory.executor(environment).start(request)

        then:
        0 * environment.exec(_)
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains('verdict')
    }
}
