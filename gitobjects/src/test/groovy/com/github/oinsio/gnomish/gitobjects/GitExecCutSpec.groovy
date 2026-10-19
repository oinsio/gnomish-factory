package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR17, FR19, design D15 of fix-terminal-receipt-convergence as amended 2026-10-17; D4, D7 of
 * make-pipe-reads-non-parking as amended 2026-10-18: {@link GitExec}'s drains never park, so only a
 * holder that keeps writing after git's exit, a {@link WritingHolder}, keeps a drain open to the
 * post-exit bound and cuts the capture; a silent holder lets both drains end as soon as they see the
 * exit. A cut on exit zero is refused with a {@link GitObjectsException}; on a non-zero exit the cut
 * rides git's stderr. Driven on {@link FakeProcess}, whose exit and pipes the spec orders (design D16);
 * the holder's writes ride the drains' own queries, so no order is left to the scheduler.
 */
class GitExecCutSpec extends Specification {

    /** A short kill grace keeps the post-exit bound, and so the cut features, near two seconds. */
    private static final ProcessSupervisor SUPERVISOR = new ProcessSupervisor(Duration.ofMillis(100))

    @TempDir
    Path gitDir

    // FR17, D15 as amended 2026-10-17; D4 of make-pipe-reads-non-parking: the flag is "still written
    // to at the bound", so a holder that keeps writing is refused whether or not git spoke first.
    def "FR19, FR17: git exiting zero while a holder keeps writing to #held is refused, naming the bound and the stream (written: '#written')"() {
        given: 'git exits cleanly while a descendant holds both pipes and keeps writing to the named ones'
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        if (written) {
            process.writeStdout(written)
        }
        writing(process)
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

        and: 'the return came on the bound, not before it'
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
        elapsed >= SUPERVISOR.postExitBound()
        elapsed <SUPERVISOR.postExitBound() + Duration.ofSeconds(2)

        and: 'the drains were stopped, not closed: once the holder lets go, both pipes still read'
        process.release()
        !closed(process.inputStream)
        !closed(process.errorStream)

        cleanup:
        process.release()

        where:
        held | written | writing
        'stdout and stderr' | 'partial' | { FakeProcess p ->
            WritingHolder.on(p.stdoutPipe()); WritingHolder.on(p.stderrPipe())
        }
        'stdout and stderr' | '' | { FakeProcess p ->
            WritingHolder.on(p.stdoutPipe()); WritingHolder.on(p.stderrPipe())
        }
        'stdout' | 'partial' | { FakeProcess p ->
            WritingHolder.on(p.stdoutPipe())
        }
        'stderr' | '' | { FakeProcess p -> WritingHolder.on(p.stderrPipe()) }
    }

    // D4, D7 of make-pipe-reads-non-parking as amended 2026-10-18: a holder that writes nothing after
    // the exit lets both drains end as soon as they see it, so nothing is cut and nothing refused.
    def "FR19, D4: git exiting #exitCode while a silent holder keeps both pipes returns the whole capture, unflagged and unrefused"() {
        given:
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        process.writeStdout('a' * 40 + '\n')
        process.writeStderr(stderr)
        process.exit(exitCode)

        when:
        long startedAt = System.nanoTime()
        GitExec.Result result = exec(process).run(['rev-parse', 'HEAD'])

        then: 'every byte git wrote, and no cut notice riding its stderr'
        result.exitCode() == exitCode
        result.stdoutText() == 'a' * 40 + '\n'
        result.stderr() == stderr
        !result.overflowed()

        and: 'the return did not wait for the bound'
        Duration.ofNanos(System.nanoTime() - startedAt) <SUPERVISOR.postExitBound()

        cleanup:
        process.release()

        where:
        exitCode | stderr
        0 | ''
        1 | 'fatal: not a valid object name\n'
    }

    // FR17, D15 as amended 2026-10-17 (Go's precedence): a failed git keeps its own failure — the
    // stderr check() reports — and the cut rides that message instead of becoming a second finding.
    def "FR19, FR17: git exiting non-zero while a holder keeps writing to stdout returns the result, the cut riding git's stderr"() {
        given: 'git fails; a descendant holds both pipes, writing to stdout and silent on stderr'
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        process.writeStderr('fatal: not a valid object name\n')
        WritingHolder.on(process.stdoutPipe())
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

        and: "git's own stderr comes first, the cut after it, naming only the stream still written to"
        result.stderr() == 'fatal: not a valid object name\n' +
                "(git output was cut: stdout still open at the post-exit bound of ${SUPERVISOR.postExitBound()}" +
                ' — a descendant held its pipes after the command exited)'

        cleanup:
        process.release()
    }

    /** Whether the read end was closed: a read of it then fails rather than returning a byte or the end. */
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
