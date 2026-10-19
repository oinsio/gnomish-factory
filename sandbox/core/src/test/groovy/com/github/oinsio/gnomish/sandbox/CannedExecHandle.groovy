package com.github.oinsio.gnomish.sandbox

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The capture specs' shared bounded runner: a wedged capture fails the feature instead of hanging it. */
class CaptureThread {

    /** Runs {@code body} on a bounded thread of its own, so a wedged capture fails instead of hanging. */
    static Throwable onItsOwnThread(Closure body) {
        def failure = new Throwable[1]
        def done = new CountDownLatch(1)
        Thread.ofPlatform().daemon(true).start {
            try {
                body()
            } catch (Throwable t) {
                failure[0] = t
            } finally {
                done.countDown()
            }
        }
        assert done.await(10, TimeUnit.SECONDS): 'the capture never returned: the pipe read blocked ahead of the wait'
        failure[0]
    }
}

/**
 * A canned {@link ExecHandle} over the one {@code Process} double (design D6 of
 * make-pipe-reads-non-parking): its output is the {@link FakeProcess}'s stdout minted as a
 * {@link ChildOutput}, its wait the process's own, or — with {@link #interruptsWait} — a wait cut
 * short by an interrupt that reports {@link #exit}, the killed process's meaningless code.
 */
class CannedExecHandle implements ExecHandle {

    FakeProcess process = new FakeProcess()
    int exit = 143
    boolean interruptsWait = false
    Duration bound = Duration.ofSeconds(5)
    CountDownLatch exited = new CountDownLatch(1)
    int boundReads = 0

    private ChildOutput stdout

    /** A child that wrote {@code stdout} and exited with {@code code}; nothing holds its pipe. */
    static FakeProcess exited(int code, String stdout) {
        def child = FakeProcess.running()
        child.writeStdout(stdout)
        child.exit(code)
        child
    }

    /** A child that wrote {@code prefix} and exited with {@code code} while a silent descendant holds its pipe. */
    static FakeProcess held(int code, String prefix) {
        def child = FakeProcess.running()
        child.writeStdout(prefix)
        child.holdOpen()
        child.exit(code)
        child
    }

    /**
     * A child that wrote {@code prefix} and exited with {@code code} while a descendant holding its
     * pipe keeps writing: a byte before every other availability query, so the drain never sees two
     * idle queries in a row and is still open at the post-exit bound.
     */
    static FakeProcess writingAfterExit(int code, String prefix) {
        def child = held(code, prefix)
        WritingHolder.on(child.stdoutPipe())
        child
    }

    /** Whether the capture stopped the output: a stopped view answers end-of-file at once, past the writer. */
    boolean stopped() {
        output().stream().read(new byte[8]) == -1
    }

    /** Minted on first use, so a map constructor's {@code process} is the one it reads. */
    @Override
    synchronized ChildOutput output() {
        if (stdout == null) {
            stdout = ChildOutput.stdout(process)
        }
        stdout
    }

    @Override
    Instant startedAt() {
        Instant.EPOCH
    }

    @Override
    ExecHandle.Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
        throw new UnsupportedOperationException('not driven by the capture helper')
    }

    @Override
    int waitForExit() {
        if (interruptsWait) {
            // The supervised wait's contract: tree killed, flag restored, exit code meaningless.
            Thread.currentThread().interrupt()
            exited.countDown()
            return exit
        }
        int code = process.waitFor()
        exited.countDown()
        code
    }

    @Override
    Duration postExitBound() {
        boundReads++
        bound
    }
}
