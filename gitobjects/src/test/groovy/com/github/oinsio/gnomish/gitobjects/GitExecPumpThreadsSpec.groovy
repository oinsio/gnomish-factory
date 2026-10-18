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
 * capped read fails" — are asserted here as their bounded successors.
 */
class GitExecPumpThreadsSpec extends Specification {

    /** A short kill grace keeps the post-exit bound, and so the held-pipe feature, near two seconds. */
    private static final ProcessSupervisor SUPERVISOR = new ProcessSupervisor(Duration.ofMillis(100))

    @TempDir
    Path gitDir

    // Scenario: lifecycle/subprocess-supervision / Every reader waits with the one bound
    def "FR19: stderr a holder writes after the exit is captured whole, within the post-exit bound"() {
        given: 'git has spoken on stderr and exits, its stderr held open as by a descendant'
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        process.writeStderr('from-git\n')
        process.onWaitReturn {
            process.writeStderr('from-the-holder\n')
            process.release()
        }
        process.exit(1)

        when:
        GitExec.Result result = exec(process).run(['cat-file', '-e', 'HEAD'])

        then: 'the result waited for the late line, not for an unbounded pump join'
        result.exitCode() == 1
        result.stderr() == 'from-git\nfrom-the-holder\n'
        !result.overflowed()
    }

    // FR17, D15 as amended 2026-10-17: the flag is "no end-of-file inside the bound", not "bytes
    // lost", so a held pipe with nothing written is refused as well as one that carried a prefix.
    def "FR19, FR17: git exiting zero while a holder keeps #held open is refused, naming the bound and the stream (written: '#written')"() {
        given: 'git exits cleanly while a descendant keeps the named pipes open'
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        if (written) {
            process.writeStdout(written)
        }
        process.onWaitReturn { released(process).close() }
        process.exit(0)

        when:
        long startedAt = System.nanoTime()
        exec(process).run([
            'cat-file',
            'blob',
            'HEAD:file'
        ])

        then: 'the cut output is refused loudly rather than handed back as complete'
        GitObjectsException failure = thrown()
        !(failure instanceof GitObjectsInterruptedException)
        failure.message == "git output was cut: ${held} still open at the post-exit bound of ${SUPERVISOR.postExitBound()}" +
                ' — a descendant held its pipes after the command exited'

        and: 'the return came on the bound'
        Duration.ofNanos(System.nanoTime() - startedAt) <SUPERVISOR.postExitBound() + Duration.ofSeconds(2)

        and: 'the drains closed their ends of the held pipes'
        closed(process.inputStream)
        closed(process.errorStream)

        cleanup:
        process.release()

        where:
        held | written | released
        'stdout and stderr' | 'partial' | { FakeProcess p ->
            InputStream.nullInputStream()
        }
        'stdout and stderr' | '' | { FakeProcess p ->
            InputStream.nullInputStream()
        }
        'stdout' | 'partial' | { FakeProcess p -> p.errorStream }
        'stderr' | '' | { FakeProcess p -> p.inputStream }
    }

    // FR17, D15 as amended 2026-10-17 (Go's precedence): a failed git keeps its own failure — the
    // stderr check() reports — and the cut rides that message instead of becoming a second finding.
    def "FR19, FR17: git exiting non-zero while a holder keeps its pipes returns the result, the cut riding git's stderr"() {
        given:
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        process.writeStderr('fatal: not a valid object name\n')
        process.exit(1)

        when:
        GitExec.Result result = exec(process).run([
            'cat-file',
            '-e',
            'HEAD:missing'
        ])

        then: 'no refusal: the exit code keeps its meaning, so exists() and check() read it unchanged'
        result.exitCode() == 1
        !result.overflowed()

        and: "git's own stderr comes first, the cut after it"
        result.stderr() == 'fatal: not a valid object name\n' +
                "(git output was cut: stdout and stderr still open at the post-exit bound of ${SUPERVISOR.postExitBound()}" +
                ' — a descendant held its pipes after the command exited)'

        cleanup:
        process.release()
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

    private static boolean closed(InputStream stream) {
        try {
            stream.read()
            false
        } catch (IOException expected) {
            true
        }
    }

    private GitExec exec(FakeProcess process) {
        new GitExec(gitDir, 'git', SUPERVISOR, { builder, stdin ->
            process
        } as ProcessLaunch)
    }
}
