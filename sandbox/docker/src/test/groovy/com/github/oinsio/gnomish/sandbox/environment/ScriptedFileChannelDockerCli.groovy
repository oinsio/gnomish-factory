package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.subprocess.CaptureRunner
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.time.Duration
import java.util.concurrent.CountDownLatch

/**
 * A scripted {@link DockerCli} + {@link Process} pair shared by the file-channel
 * mechanics and boundary specs (FR1, NFR-S3 of add-sandbox-core): {@code run()}
 * is never expected here (file-channel operations only ever {@code start()} an
 * exec'd shell). The real {@code start()} runs, over a recording launch handed in
 * through the constructor's capture runner — the seam every reader of a child's
 * output takes (design D16 of fix-terminal-receipt-convergence) — which answers
 * with the single scripted {@link #process}, or {@link #handed} when a spec sets
 * one, recording every argv (without the leading {@code docker}) and every
 * {@link StdinSource} for assertions.
 *
 * <p>Its supervisor has no kill grace, so the post-exit bound a channel joins its
 * drains with is the supervisor's margin alone: a spec that holds a pipe open past
 * the exit waits seconds, not the production grace (design D17 of
 * fix-terminal-receipt-convergence).
 */
class ScriptedFileChannelDockerCli extends DockerCli {

    private final RecordingLaunch launch

    ScriptedFileChannelDockerCli() {
        this(new RecordingLaunch())
    }

    private ScriptedFileChannelDockerCli(RecordingLaunch launch) {
        super('docker', DockerCli.DEFAULT_COMMAND_TIMEOUT,
                new CaptureRunner(new ProcessSupervisor(Duration.ZERO), launch))
        this.launch = launch
    }

    List<List<String>> getStarts() {
        launch.starts
    }

    List<StdinSource> getSources() {
        launch.sources
    }

    ScriptedFileChannelProcess getProcess() {
        launch.process
    }

    void setHanded(Process handed) {
        launch.handed = handed
    }

    @Override
    DockerResult run(List<String> args) {
        throw new IllegalStateException('run() not expected in a file-channel spec: ' + args)
    }

    /** The bytes the channel handed the launch as the exec's input; the accessor is the module's own. */
    byte[] fed(int index) {
        sources[index].bytes()
    }
}

/**
 * The launch the scripted CLI's capture runner holds: it starts nothing, records the docker argv
 * (without the leading binary) and the input source, and answers with the scripted process.
 */
class RecordingLaunch implements ProcessLaunch {

    final List<List<String>> starts = []
    final List<StdinSource> sources = []
    final ScriptedFileChannelProcess process = new ScriptedFileChannelProcess()
    Process handed = null

    @Override
    Process start(ProcessBuilder builder, StdinSource stdin) {
        starts << builder.command().drop(1)
        sources << stdin
        handed ?: process
    }
}

/**
 * A scripted exec'd process for the file-channel specs: stdout is canned bytes
 * (or, for the stall spec, a caller-supplied {@link InputStream}), and {@code
 * waitFor} either honours a real pending interrupt (throws and clears the flag,
 * matching {@link Process#waitFor()}) or — when {@link #interruptOnWaitFor} is
 * set — completes normally while leaving the flag set on the calling thread,
 * scripting an interrupt that lands on the drain joins that follow the wait.
 *
 * <p>{@link #exitsOnlyWhenStderrDrained} models the one thing an in-JVM fake
 * otherwise cannot: a real child cannot exit while a pipe nobody reads is full,
 * so a channel that leaves stderr undrained wedges its own wait.
 */
class ScriptedFileChannelProcess extends FakeProcess {

    byte[] stdout = new byte[0]
    InputStream stdoutStream = null
    InputStream stderrStream = null
    int exit = 0
    boolean interruptOnWaitFor = false
    boolean exitsOnlyWhenStderrDrained = false

    private final CountDownLatch stderrDrained = new CountDownLatch(1)

    @Override
    InputStream getInputStream() {
        stdoutStream ?: new ByteArrayInputStream(stdout)
    }

    @Override
    InputStream getErrorStream() {
        new EofSignallingStream(stderrStream ?: new ByteArrayInputStream(new byte[0]), stderrDrained)
    }

    @Override
    int waitFor() throws InterruptedException {
        if (interruptOnWaitFor) {
            Thread.currentThread().interrupt()
            return exit
        }
        if (exitsOnlyWhenStderrDrained) {
            stderrDrained.await()
            return exit
        }
        if (Thread.interrupted()) {
            throw new InterruptedException('interrupted while waiting')
        }
        exit
    }

    @Override
    int exitValue() {
        exit
    }
}

/** Counts down {@code drained} once its source has been read to the end. */
class EofSignallingStream extends FilterInputStream {

    private final CountDownLatch drained

    EofSignallingStream(InputStream source, CountDownLatch drained) {
        super(source)
        this.drained = drained
    }

    @Override
    int read(byte[] buffer, int off, int len) {
        int read = super.read(buffer, off, len)
        if (read < 0) {
            drained.countDown()
        }
        return read
    }
}
