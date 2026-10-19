package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ChildOutput
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Both round executions send the CLI's stderr to a file in the round scratch directory, read its
 * tail into the round's diagnostics after the exit — the failure's detail, or one DEBUG line on
 * success — and leave no file of the round on the host, however the round ended (design D18 of fix-terminal-receipt-convergence).
 *
 * <p>FR20 of fix-terminal-receipt-convergence.
 */
class RoundStderrFileSpec extends Specification {

    static final String PASSING_STREAM =
    '{"type":"result","subtype":"success","session_id":"s-1","result":"{\\"passed\\": true}"}\n'

    @TempDir
    Path workspaceDir

    @TempDir
    Path scratchParent

    ExecCommand seen

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
    }

    private AgentRoundEquipment equipment(Path parent) {
        new AgentRoundEquipment(new FactoryProperties('factory-01', 'claude', null, null),
                new VirtualClock(), { event -> }, new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), parent)
    }

    private TaskExecutionEnvironment environment(Closure<ExecHandle> launch) {
        Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> { ExecCommand command ->
                seen = command
                launch()
            }
        }
    }

    private Object execute(String role, TaskExecutionEnvironment environment, Path parent = scratchParent) {
        if (role == 'executor') {
            return ExecutorRoundExecution.run(equipment(parent), new DecisionFileReader(),
                    StageExecutorRequests.request(workspaceDir), 'prompt',
                    new StandInRound(environment, workspaceDir.resolve('decision.json')))
        }
        JudgeRoundExecution.run(equipment(parent), new JudgeVerdictExtractor(),
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1), environment, 'prompt')
    }

    private static ChildOutput stream(String text) {
        CannedOutputs.written(text)
    }

    private Path stderrFile() {
        (seen.stderr() as StderrPolicy.ToFile).file()
    }

    // FR20: the stderr is a file in the round scratch directory, under the equipment's parent
    def "FR20: the #role round sends the CLI's stderr to a file in its scratch directory"() {
        when:
        execute(role, environment {
            new ScratchStderrHandle({
                stderrFile()
            }, stream(PASSING_STREAM), new ExecHandle.Wait.Exited(Duration.ofSeconds(1)), '')
        })

        then:
        seen.stderr() instanceof StderrPolicy.ToFile
        stderrFile().parent.parent == scratchParent

        where:
        role << ['executor', 'judge']
    }

    // FR20: no file of the round remains on the host after it ended, however it ended
    // Scenario: executor/agent-executor / The stderr file leaves with the round
    def "FR20: after a #role round that #ending, no file of the round remains on the host"() {
        when:
        try {
            execute(role, environment {
                if (wait == null) {
                    throw new ProcessStartException('could not start process: claude', new IOException('no such binary'))
                }
                new ScratchStderrHandle({
                    stderrFile()
                }, stream(content), wait, 'some stderr\n')
            })
        } catch (ExecutorFailure ignored) {
            // the executor ends a failed round by an exception; its teardown is the subject
        }

        then:
        seen != null
        !Files.exists(stderrFile().parent)
        Files.list(scratchParent).count() == 0

        where:
        [role, ending, content, wait] << [
            ['executor', 'judge'],
            [
                [
                    'exited normally',
                    PASSING_STREAM,
                    new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
                ],
                [
                    'was killed on its timeout',
                    '',
                    new ExecHandle.Wait.TimedOut()
                ],
                [
                    'was interrupted',
                    '',
                    new ExecHandle.Wait.Interrupted()
                ],
                [
                    'carried no result event',
                    '{"type":"system"}\n',
                    new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
                ],
                ['never started', null, null],
            ]
        ].combinations().collect { r, row -> [r] + row }
    }

    // FR20: a chatty stderr cannot block the round; the diagnostics carry its tail
    // Scenario: executor/agent-executor / A chatty stderr cannot block the round
    def "FR20: a #role round whose CLI wrote more than a pipe buffer to stderr completes, and its diagnostics carry the tail"() {
        given:
        def stderr = 'HEAD' + ('x' * (200 * 1024)) + 'the error at the end'
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        def outcome = execute(role, environment {
            new ScratchStderrHandle({
                stderrFile()
            }, stream(PASSING_STREAM), new ExecHandle.Wait.Exited(Duration.ofSeconds(1)), stderr)
        })

        then:
        role == 'executor' ? outcome instanceof ExecutionResult.Completed : outcome.verdict() instanceof Verdict.Pass
        logs.list*.formattedMessage.any {
            it.contains('the error at the end') && !it.contains('HEAD')
        }

        where:
        role << ['executor', 'judge']
    }

    // FR20: a scratch directory that cannot be made is a round that cannot start, on each contract
    def "FR20: an executor round whose scratch directory cannot be made fails as infrastructure, before any launch"() {
        when:
        execute('executor', environment {
            throw new AssertionError('no launch expected')
        }, scratchParent.resolve('missing'))

        then:
        def failure = thrown(ExecutorFailure)
        failure.cause() instanceof UncheckedIOException
        seen == null
    }

    def "FR20: a judge round whose scratch directory cannot be made cannot verify, before any launch"() {
        when:
        def vote = execute('judge', environment {
            throw new AssertionError('no launch expected')
        }, scratchParent.resolve('missing'))

        then:
        vote.verdict() instanceof Verdict.CannotVerify
        (vote.verdict() as Verdict.CannotVerify).reason().forLog().contains('scratch directory')
        seen == null
    }
}
