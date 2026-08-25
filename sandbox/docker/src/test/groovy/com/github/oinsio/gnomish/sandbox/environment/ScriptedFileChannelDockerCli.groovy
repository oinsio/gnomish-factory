package com.github.oinsio.gnomish.sandbox.environment

import java.util.concurrent.CountDownLatch

/**
 * A scripted {@link DockerCli} + {@link Process} pair shared by the file-channel
 * mechanics and boundary specs (FR1, NFR-S3 of add-sandbox-core): {@code run()}
 * is never expected here (file-channel operations only ever {@code start()} an
 * exec'd shell), and {@code start()} always answers with the single scripted
 * {@link #process}, recording every argv for assertions.
 */
class ScriptedFileChannelDockerCli extends DockerCli {

    final List<List<String>> starts = []
    final ScriptedFileChannelProcess process = new ScriptedFileChannelProcess()

    ScriptedFileChannelDockerCli() {
        super('docker')
    }

    @Override
    DockerResult run(List<String> args) {
        throw new IllegalStateException('run() not expected in a file-channel spec: ' + args)
    }

    @Override
    Process start(List<String> args, boolean mergeStderr) {
        starts << args
        process
    }
}

/**
 * A scripted exec'd process for the file-channel specs: stdout is canned bytes
 * (or, for the stall spec, a caller-supplied {@link InputStream}), stdin is a
 * {@link SlowCloseStdin} sink a spec can delay or inspect, and {@code waitFor}
 * either honours a real pending interrupt (throws and clears the flag, matching
 * {@link Process#waitFor()}) or — when {@link #interruptOnWaitFor} is set —
 * completes normally while leaving the flag set on the calling thread, scripting
 * an interrupt that lands on the stdin pump join that follows inside
 * {@code ContainerFileChannel#putFile}.
 *
 * <p>{@link #exitsOnlyWhenStderrDrained} models the one thing an in-JVM fake
 * otherwise cannot: a real child cannot exit while a pipe nobody reads is full,
 * so a channel that leaves stderr undrained wedges its own wait.
 */
class ScriptedFileChannelProcess extends FakeProcess {

    final SlowCloseStdin stdin = new SlowCloseStdin()
    byte[] stdout = new byte[0]
    InputStream stdoutStream = null
    InputStream stderrStream = null
    int exit = 0
    boolean interruptOnWaitFor = false
    boolean exitsOnlyWhenStderrDrained = false

    private final CountDownLatch stderrDrained = new CountDownLatch(1)

    @Override
    OutputStream getOutputStream() {
        stdin
    }

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

/** A stdin sink that can be told to take a while to close, to script a slow-finishing pump. */
class SlowCloseStdin extends ByteArrayOutputStream {

    volatile boolean closed = false
    long closeDelayMillis = 0

    @Override
    void close() {
        if (closeDelayMillis> 0) {
            Thread.sleep(closeDelayMillis)
        }
        closed = true
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
