package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR19, FR23, design D17 and D20 of fix-terminal-receipt-convergence: {@link GitExec} reads both of
 * git's output streams on the subprocess module's one drain, joined after the wait with the
 * supervisor's post-exit bound — no pump thread of its own, no unbounded join, no stdout read before
 * the wait — and hands its stdin bytes to the launch owner as a source rather than pumping them.
 *
 * <p>Driven on {@link FakeProcess}, whose exit and pipe ends the spec orders (design D16): the shapes
 * that pinned the old pump joins — "await waits for the stderr pump", "await still runs when the
 * capped read fails" — are asserted here as their bounded successors. The cut verdict on a holder
 * after the exit is {@code GitExecCutSpec}'s.
 */
class GitExecPumpThreadsSpec extends Specification {

    /** A short kill grace keeps the post-exit bound, and so the late-line feature, near two seconds. */
    private static final ProcessSupervisor SUPERVISOR = new ProcessSupervisor(Duration.ofMillis(100))

    @TempDir
    Path gitDir

    // Scenario: lifecycle/subprocess-supervision / Every reader waits with the one bound
    def "FR19: stderr a holder writes after the exit while the drain still reads is captured whole, within the post-exit bound"() {
        given: 'git has spoken on stderr and exits, its stderr held open as by a descendant'
        // The order is the spec's, not the scheduler's (design D2 of make-pipe-reads-non-parking): the
        // drain's first query takes what git spoke, and just before its second the holder speaks and
        // releases the pipe, while the drain still reads. A line the holder speaks only after the drain
        // saw the exit is dropped, as the JDK's reaper drops it.
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        process.writeStderr('from-git\n')
        process.stderrPipe().onQuery {
            process.stderrPipe().onQuery {
                process.writeStderr('from-the-holder\n')
                process.release()
            }
        }
        process.exit(1)

        when:
        GitExec.Result result = exec(process).run(['cat-file', '-e', 'HEAD'])

        then: 'the result waited for the late line through the bounded join, not an unbounded pump join'
        result.exitCode() == 1
        result.stderr() == 'from-git\nfrom-the-holder\n'
        !result.overflowed()
    }

    def "FR23: the stdin bytes reach the launch owner as a source, and no stdin as NONE"() {
        given:
        List<StdinSource> handed = []
        GitExec exec = new GitExec(gitDir, 'git', SUPERVISOR, { builder, stdin ->
            handed << stdin
            FakeProcess process = FakeProcess.running()
            process.exit(0)
            process
        } as ProcessLaunch)

        when:
        exec.run([
            'hash-object',
            '-w',
            '--stdin'
        ], 'blob'.bytes, [:], -1)
        exec.run(['rev-parse', 'HEAD'])

        then:
        handed[0] instanceof StdinSource.Content
        handed[1].is(StdinSource.NONE)
    }

    private GitExec exec(FakeProcess process) {
        new GitExec(gitDir, 'git', SUPERVISOR, { builder, stdin ->
            process
        } as ProcessLaunch)
    }
}
