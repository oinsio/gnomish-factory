package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.RefusingLaunch
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The round's stderr on the real host path: the fake agent as a real process, launched through
 * the launch owner into a host environment, its stderr written by the process itself into the
 * round scratch directory (design D18 of fix-terminal-receipt-convergence), its stdin a file of
 * the launch owner (design D20).
 *
 * <p>FR20, FR23 of fix-terminal-receipt-convergence.
 */
class HostRoundStderrSpec extends Specification {

    static final String ERROR_LINE = 'claude: error: the error at the end of a chatty stderr'

    @TempDir
    Path workspaceDir

    @TempDir
    Path scratchParent

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
    }

    private ExecutionResult run(String scenario, String prompt = 'prompt', ProcessLaunch launch = ProcessLaunch.grouped()) {
        def clock = new VirtualClock()
        def environment = new HostTaskExecutionEnvironment(
                workspaceDir, clock, TestChildEnvironment.hostAllowlist(), launch)
        def equipment = new AgentRoundEquipment(FakeAgentSupport.propertiesFor(scenario), clock, { event -> },
        new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), scratchParent)
        ExecutorRoundExecution.run(equipment, new DecisionFileReader(),
                FakeAgentSupport.requestFor(workspaceDir, ['roundTimeout': '60s']), prompt,
                new StandInRound(environment, workspaceDir.resolve('decision.json')))
    }

    // FR20: ~200 KiB of stderr — far past a pipe buffer — neither blocks the CLI nor is lost
    // Scenario: executor/agent-executor / A chatty stderr cannot block the round
    def "FR20: a CLI writing 200 KiB to its stderr completes the round, and the diagnostics carry the tail"() {
        given:
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        long startedAt = System.nanoTime()
        def result = run('chatty-stderr')
        long elapsedMillis = (System.nanoTime() - startedAt).intdiv(1_000_000)

        then: 'the round completed on the exit, nowhere near its budget'
        result instanceof ExecutionResult.Completed
        elapsedMillis < 30_000

        and: 'the tail of the stderr is the one DEBUG line of the successful round'
        logs.list.size() == 1
        logs.list[0].level == Level.DEBUG
        logs.list[0].formattedMessage.contains(ERROR_LINE)

        and: 'no file of the round remains on the host'
        Files.list(scratchParent).count() == 0
    }

    // FR20: the failure of a real process carries what the process said last
    // Scenario: executor/agent-executor / A failed round carries its stderr tail
    def "FR20: a CLI that exits without a result event fails the round with its stderr tail, and leaves no file"() {
        when:
        run('chatty-stderr-no-result')

        then:
        def failure = thrown(ExecutorFailure)
        failure.cause() instanceof MissingResultEventException
        failure.stderrTail().contains(ERROR_LINE)
        failure.stderrTail().length() == RoundScratchDirectory.STDERR_TAIL_BYTES

        and:
        Files.list(scratchParent).count() == 0
    }

    // FR21 (D19 as amended 2026-10-17, second): no classifier in the round — the launcher's refusal
    //     is a round with no result event, and its line is what the operator reads in the tail
    // Scenario: lifecycle/subprocess-supervision / A launcher's refusal reaches a streaming waiter in its diagnostics
    def "FR21: a CLI the launcher could not exec fails the round as a missing result, its refusal line in the stderr tail"() {
        when:
        run('chatty-stderr', 'prompt', new RefusingLaunch())

        then:
        def failure = thrown(ExecutorFailure)
        failure.cause() instanceof MissingResultEventException
        failure.stderrTail().contains('gnomish-launch: cannot exec ')

        and:
        Files.list(scratchParent).count() == 0
    }

    // FR23 (D20): the prompt reaches the CLI from a file the launch owner opened, so a background
    //     job that keeps the CLI's stdin open — its output redirected away, so only the stdin is
    //     held — cannot keep the round past the CLI's exit; the prompt is larger than a pipe buffer.
    def "FR23: a CLI that exits while a background job holds its stdin open returns the round at the exit"() {
        when:
        long startedAt = System.nanoTime()
        def result = run('stdin-held', 'p' * (100 * 1024))
        long elapsedMillis = (System.nanoTime() - startedAt).intdiv(1_000_000)

        then: 'the round returned at the exit, not after the 60 s the holder keeps the stdin'
        result instanceof ExecutionResult.Completed
        elapsedMillis < 30_000
    }
}
