package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.claude.AgentRoundResultExtractor
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * A round that does not succeed carries the tail of its CLI's stderr in its failure's detail —
 * the executor's {@code failed/infrastructure} reason — on every path that ends it (the judge's
 * round is the agent round runner's since task 4.1 of extract-agent-round-runner, pinned by its own
 * specs); a successful round leaves the tail as one DEBUG line and nowhere
 * else (design D18 of fix-terminal-receipt-convergence, as amended 2026-10-17).
 *
 * <p>FR20 of fix-terminal-receipt-convergence.
 */
class RoundStderrTailSpec extends Specification {

    static final String STDERR = 'claude: error: not logged in'

    static final String PASSING_STREAM =
    '{"type":"result","subtype":"success","session_id":"s-1","result":"{\\"passed\\": true}"}\n'

    @TempDir
    Path workspaceDir

    @TempDir
    Path scratchParent

    ExecCommand seen

    LogCaptureSupport roundLogs

    def setup() {
        roundLogs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)
    }

    def cleanup() {
        roundLogs.detach()
    }

    private Path stderrFile() {
        (seen.stderr() as StderrPolicy.ToFile).file()
    }

    private Object execute(String role, Closure<ExecHandle> launch) {
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> { ExecCommand command ->
                seen = command
                launch()
            }
        }
        def equipment = new AgentRoundEquipment(new FactoryProperties('factory-01', 'claude', null, null),
                new VirtualClock(), { event -> }, new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), scratchParent)
        ExecutorRoundExecution.run(equipment,
                StageExecutorRequests.request(workspaceDir), 'prompt',
                new StandInRound(environment, workspaceDir.resolve('decision.json')))
    }

    /** What the failure carries as its detail: the executor's reason. */
    private static String detailOf(Object outcome) {
        InfrastructureResults.of(outcome).reason().forLog()
    }

    private ExecHandle handle(String stdout, ExecHandle.Wait wait) {
        new ScratchStderrHandle({
            stderrFile()
        }, CannedOutputs.written(stdout), wait, STDERR)
    }

    // FR20: the tail rides the failure on every path that ends a round without success
    // Scenario: executor/agent-executor / A failed round carries its stderr tail
    def "FR20: a #role round that #ending carries its CLI's stderr tail in the failure's detail"() {
        when:
        def outcome = execute(role, launch.curry(this))

        then: 'the failure carries the tail, as untrusted text'
        outcome instanceof ExecutionResult
        detailOf(outcome).contains(STDERR)

        and: 'nothing logs the tail at DEBUG beside it'
        roundLogs.list.isEmpty()

        and: 'the tail was read before the directory left'
        !Files.exists(stderrFile().parent)

        where:
        [role, ending, launch] << [
            ['executor'],
            [
                [
                    'failed to start',
                    { RoundStderrTailSpec spec ->
                        Files.writeString(spec.stderrFile(), STDERR)
                        throw new ProcessStartException('could not start process: claude', new IOException('exec failed'))
                    }
                ],
                [
                    'exited without a result event',
                    { RoundStderrTailSpec spec ->
                        spec.handle('{"type":"system"}\n', new ExecHandle.Wait.Exited(Duration.ofSeconds(1)))
                    }
                ],
                [
                    'timed out',
                    { RoundStderrTailSpec spec ->
                        spec.handle('', new ExecHandle.Wait.TimedOut())
                    }
                ],
                [
                    'was interrupted',
                    { RoundStderrTailSpec spec ->
                        spec.handle('', new ExecHandle.Wait.Interrupted())
                    }
                ],
                [
                    'left its stdout drain unfinished past the post-exit bound',
                    { RoundStderrTailSpec spec ->
                        new ScratchStderrHandle({
                            spec.stderrFile()
                        }, CannedOutputs.nonEnding(FakeProcess.running()),
                        new ExecHandle.Wait.Exited(Duration.ofSeconds(1)), STDERR, Duration.ofMillis(100))
                    }
                ],
            ]
        ].combinations().collect { r, row -> [r] + row }
    }

    // FR20: a failure whose CLI wrote nothing to stderr carries nothing more than before
    def "FR20: a #role round that timed out with an empty stderr carries the failure's own detail only"() {
        when:
        def outcome = execute(role) {
            new ScratchStderrHandle({
                stderrFile()
            }, CannedOutputs.written(''), new ExecHandle.Wait.TimedOut(), '')
        }

        then:
        !detailOf(outcome).contains('stderr')

        where:
        role << ['executor']
    }

    // FR20: on success the tail is one DEBUG line, and no failure detail exists to carry it
    // Scenario: executor/agent-executor / A failed round carries its stderr tail
    def "FR20: a successful #role round logs its stderr tail at DEBUG only"() {
        when:
        def outcome = execute(role) {
            handle(PASSING_STREAM, new ExecHandle.Wait.Exited(Duration.ofSeconds(1)))
        }

        then:
        outcome.status() instanceof ExecutionResult.Completed
        roundLogs.list.size() == 1
        roundLogs.list[0].level == Level.DEBUG
        roundLogs.list[0].formattedMessage.contains(STDERR)

        where:
        role << ['executor']
    }
}
