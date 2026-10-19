package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import com.github.oinsio.gnomish.subprocess.CaptureRunner
import com.github.oinsio.gnomish.subprocess.CutCaptureException
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-O3, FR17, design D15 of fix-terminal-receipt-convergence as amended 2026-10-17: a clean git
 * exit the post-exit bound cut is refused by the capture runner; the runner logs the holder once —
 * the one place that knows both the command and the bound — and rethrows the refusal, so no parser
 * receives the prefix; a failed command keeps its own failure. The second half of {@code
 * CaptureRunnerPostExitSpec}, which pins the refusal itself in {@code :subprocess}.
 *
 * <p>Driven by a {@link FakeProcess} handed in through the runner's {@link CaptureRunner} (design
 * D16): the spec holds the pipe past the exit itself, so no script's timing decides the outcome. A
 * drain that never parks reaches the bound only while a holder keeps writing after the exit (design
 * D4 of make-pipe-reads-non-parking): the holder is a {@link WritingHolder} on stdout, whose writes
 * ride the drain's own queries; a silent holder lets the capture end complete and unflagged.
 */
class GitProcessRunnerTruncatedCaptureSpec extends Specification {

    @TempDir
    Path cwd

    private static final Duration KILL_GRACE = Duration.ofMillis(100)

    private static GitProcessRunner runnerOver(FakeProcess process, Duration networkTimeout) {
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            process
        } as ProcessLaunch
        new GitProcessRunner('git', networkTimeout, new CaptureRunner(new ProcessSupervisor(KILL_GRACE), launch))
    }

    private static List<?> warnings(Closure emit) {
        LogCaptureSupport.capture(GitProcessRunner, Level.DEBUG, emit).findAll {
            it.level == Level.WARN
        }
    }

    // Scenario: lifecycle/subprocess-supervision / A cut capture says so
    def "NFR-O3, FR17: a clean exit whose pipe a holder kept past the bound logs one coded WARN, then is refused"() {
        given: 'git speaks, exits cleanly, and a descendant holds its stdout and keeps writing to it'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('spoken')
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.releaseStderr()
        process.exit(0)
        def runner = runnerOver(process, Duration.ofSeconds(30))

        when:
        CutCaptureException refusal = null
        def lines = warnings {
            try {
                runner.run(cwd, 'rev-parse', 'HEAD')
            } catch (CutCaptureException e) {
                refusal = e
            }
        }

        then: 'no result reaches a parser: the refusal is rethrown, the prefix inside it'
        refusal != null
        refusal.captured().exitCode() == 0
        refusal.captured().stdout().startsWith('spoken')

        and: 'exactly one WARN before it, under its own code, naming the subcommand, the stream and the bound'
        lines.size() == 1
        lines[0].formattedMessage.startsWith(OperatorEvent.GIT_CAPTURE_TRUNCATED.head())
        lines[0].formattedMessage.contains('held its stdout when')
        lines[0].formattedMessage.contains('subcommand=rev-parse')
        lines[0].formattedMessage.contains('bound=' + new ProcessSupervisor(KILL_GRACE).postExitBound())

        cleanup:
        process.release()
    }

    // Scenario: lifecycle/subprocess-supervision / A failed or killed invocation keeps its partial output
    def "FR17: a failed exit whose pipe a holder kept stands as its own failure, the cut named in its stderr"() {
        given: 'git fails while a descendant holds its pipes and keeps writing to stdout'
        FakeProcess process = FakeProcess.running()
        process.writeStderr('fatal: bad revision\n')
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.exit(128)
        def runner = runnerOver(process, Duration.ofSeconds(30))

        when:
        GitCommandResult result = null
        def lines = warnings { result = runner.run(cwd, 'rev-parse', 'nope') }

        then: 'the failure is returned with its own exit code, no refusal and no second line'
        result.termination() == Termination.EXITED
        result.exitCode() == 128
        lines.isEmpty()

        and: 'its stderr stays readable and names the cut'
        result.stderr().contains('fatal: bad revision')
        result.stderr().contains('output was cut: stdout still open at the post-exit bound')

        cleanup:
        process.release()
    }

    def "NFR-O3, D4: a clean exit whose pipes a silent holder kept returns the whole capture, unflagged"() {
        given: 'git speaks and exits cleanly; a descendant holds both pipes and writes nothing more'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('spoken')
        process.holdOpen()
        process.exit(0)
        def runner = runnerOver(process, Duration.ofSeconds(30))

        when:
        GitCommandResult result = null
        def lines = warnings { result = runner.run(cwd, 'rev-parse', 'HEAD') }

        then: 'no refusal, no line: the drains ended at the exit with all git said'
        result.termination() == Termination.EXITED
        result.exitCode() == 0
        result.stdout().forParsing() == 'spoken'
        lines.isEmpty()

        cleanup:
        process.release()
    }

    def "NFR-O3: a clean exit with nothing holding its pipes logs nothing above DEBUG"() {
        given:
        FakeProcess process = FakeProcess.running()
        process.writeStdout('spoken')
        process.exit(0)
        def runner = runnerOver(process, Duration.ofSeconds(30))

        when:
        GitCommandResult result = null
        def lines = warnings { result = runner.run(cwd, 'status') }

        then:
        result.exitCode() == 0
        lines.isEmpty()
    }

    def "NFR-O3: a timed-out command whose pipe stays held says only that it timed out"() {
        given: 'a network command that never exits by itself, its pipe held and written to past the kill'
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        def runner = runnerOver(process, Duration.ofMillis(200))

        when:
        GitCommandResult result = null
        def lines = warnings { result = runner.run(cwd, 'ls-remote', 'origin') }

        then: 'one line for one fault: the timeout already says the output is partial'
        result.termination() == Termination.TIMED_OUT
        lines.size() == 1
        lines[0].formattedMessage.startsWith(OperatorEvent.GIT_NETWORK_COMMAND_TIMED_OUT.head())

        and: 'the cut is named in the failure detail, not as a second finding'
        result.stderr().contains('output was cut')

        cleanup:
        process.release()
    }
}
