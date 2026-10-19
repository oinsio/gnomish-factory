package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.subprocess.CaptureRunner
import com.github.oinsio.gnomish.subprocess.CutCaptureException
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import spock.lang.Specification

/**
 * NFR-O3, FR17, design D15 of fix-terminal-receipt-convergence as amended 2026-10-17: a clean
 * docker exit the post-exit bound cut is refused by the capture runner; the CLI logs the holder
 * once and rethrows the refusal; a failed command keeps its own failure. The docker twin of
 * {@code GitProcessRunnerTruncatedCaptureSpec}; the flag itself is pinned in {@code :subprocess}
 * by {@code CaptureRunnerPostExitSpec}.
 *
 * <p>Driven by a {@link FakeProcess} handed in through the CLI's {@link CaptureRunner} (design D16).
 * A drain that never parks reaches the bound only while a holder keeps writing after the exit
 * (design D4 of make-pipe-reads-non-parking): the holder is a {@link WritingHolder} on stdout, whose
 * writes ride the drain's own queries, and the dots it writes follow the spoken prefix.
 */
class DockerCliTruncatedCaptureSpec extends Specification {

    private static final Duration KILL_GRACE = Duration.ofMillis(100)

    private static DockerCli cliOver(FakeProcess process, Duration commandTimeout) {
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            process
        } as ProcessLaunch
        new DockerCli('docker', commandTimeout, new CaptureRunner(new ProcessSupervisor(KILL_GRACE), launch))
    }

    private static List<?> warnings(Closure emit) {
        LogCaptureSupport.capture(DockerCommandReport, Level.DEBUG, emit).findAll {
            it.level == Level.WARN
        }
    }

    // Scenario: lifecycle/subprocess-supervision / A cut capture says so
    def "NFR-O3, FR17: a clean exit whose pipe a holder kept past the bound logs one coded WARN, then is refused"() {
        given: 'docker speaks, exits cleanly, and a descendant holds its pipes and keeps writing to stdout'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('abc123')
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.exit(0)
        def cli = cliOver(process, Duration.ofSeconds(30))

        when:
        CutCaptureException refusal = null
        def lines = warnings {
            try {
                cli.run(['inspect', 'box'])
            } catch (CutCaptureException e) {
                refusal = e
            }
        }

        then: 'no result reaches a parser: the refusal is rethrown, the prefix inside it'
        refusal != null
        refusal.captured().exitCode() == 0
        refusal.captured().stdout().startsWith('abc123')

        and: 'exactly one WARN before it, under its own code, naming the subcommand, the streams and the bound'
        lines.size() == 1
        lines[0].formattedMessage.startsWith(OperatorEvent.DOCKER_CAPTURE_TRUNCATED.head())
        lines[0].formattedMessage.contains('held its stdout when')
        lines[0].formattedMessage.contains('subcommand=inspect')
        lines[0].formattedMessage.contains('bound=' + new ProcessSupervisor(KILL_GRACE).postExitBound())

        cleanup:
        process.release()
    }

    // Scenario: lifecycle/subprocess-supervision / A failed or killed invocation keeps its partial output
    def "FR17: a failed exit whose pipe a holder kept stands as its own failure, the cut named in its stderr"() {
        given: 'docker fails while a descendant holds its pipes and keeps writing to stdout'
        FakeProcess process = FakeProcess.running()
        process.writeStderr('Error: No such object: box\n')
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.exit(1)
        def cli = cliOver(process, Duration.ofSeconds(30))

        when:
        DockerResult result = null
        def lines = warnings { result = cli.run(['inspect', 'box']) }

        then: 'the failure is returned with its own exit code, no refusal and no second line'
        result.termination() == Termination.EXITED
        result.exitCode() == 1
        lines.isEmpty()

        and: 'its stderr stays readable and names the cut'
        result.stderr().contains('No such object: box')
        result.stderr().contains('output was cut: stdout still open at the post-exit bound')

        cleanup:
        process.release()
    }

    def "NFR-O3: a clean exit with nothing holding its pipes logs nothing above DEBUG"() {
        given:
        FakeProcess process = FakeProcess.running()
        process.exit(0)
        def cli = cliOver(process, Duration.ofSeconds(30))

        when:
        DockerResult result = null
        def lines = warnings { result = cli.run(['ps']) }

        then:
        result.exitCode() == 0
        lines.isEmpty()
    }

    def "NFR-O3: a timed-out command whose pipe stays held says only that it timed out"() {
        given: 'a command that never exits by itself, its pipe held and written to past the kill'
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        def cli = cliOver(process, Duration.ofMillis(200))

        when:
        DockerResult result = null
        def lines = warnings { result = cli.run(['run', 'image']) }

        then: 'one line for one fault: the timeout already says the output is partial'
        result.termination() == Termination.TIMED_OUT
        lines.size() == 1
        lines[0].formattedMessage.startsWith(OperatorEvent.DOCKER_COMMAND_TIMED_OUT.head())

        and: 'the cut is named in the failure detail, not as a second finding'
        result.stderr().contains('output was cut')

        cleanup:
        process.release()
    }
}
