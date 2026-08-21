package com.github.oinsio.gnomish.sandbox.environment

import java.util.concurrent.CountDownLatch

/**
 * Shared scripted test doubles for {@link ContainerTaskExecutionEnvironment} exec
 * specs: a {@link DockerCli} whose every management call (inspect/create/...)
 * succeeds and whose every {@code start} returns one scripted {@link Process}.
 * Extracted from {@code ContainerTaskExecutionEnvironmentExecSpec} so
 * {@code ContainerExecHandleSpec} shares the same fixture rather than
 * duplicating it.
 */
final class ScriptedDockerCli extends DockerCli {
    final List<List<String>> starts = []
    private final Process process

    ScriptedDockerCli(Process process) {
        super('docker')
        this.process = process
    }

    @Override
    DockerResult run(List<String> args) {
        args[0] == 'inspect' ? new DockerResult(1, '', 'No such object') : new DockerResult(0, '', '')
    }

    @Override
    Process start(List<String> args, boolean mergeStderr) {
        starts << args
        process
    }
}

/** Captures the child's stdin bytes and signals when the pipe is closed. */
final class LatchedStdin extends ByteArrayOutputStream {
    final CountDownLatch closed = new CountDownLatch(1)

    @Override
    void close() {
        closed.countDown()
    }
}

/** A {@link Process} double with configurable stdout/exit code and captured stdin. */
final class ScriptedProcess extends FakeProcess {
    final LatchedStdin stdin = new LatchedStdin()
    byte[] stdout = new byte[0]
    int exit = 0

    @Override
    OutputStream getOutputStream() {
        stdin
    }

    @Override
    InputStream getInputStream() {
        new ByteArrayInputStream(stdout)
    }

    @Override
    int waitFor() {
        exit
    }

    @Override
    int exitValue() {
        exit
    }
}
