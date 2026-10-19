package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.subprocess.CaptureRunner
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.time.Duration

/**
 * A scripted {@link DockerCli} + {@link Process} pair shared by the file-channel
 * mechanics and boundary specs (FR1, NFR-S3 of add-sandbox-core): {@code run()}
 * is never expected here (file-channel operations only ever {@code start()} an
 * exec'd shell). The real {@code start()} runs, over a recording launch handed in
 * through the constructor's capture runner — the seam every reader of a child's
 * output takes (design D16 of fix-terminal-receipt-convergence) — which answers
 * every start with a fresh process played from the one {@link #script}, or with
 * {@link #handed} when a spec sets one, recording every argv (without the leading
 * {@code docker}) and every {@link StdinSource} for assertions.
 *
 * <p>Its supervisor has no kill grace, so the post-exit bound a channel joins its
 * drains with is the supervisor's margin alone: a spec whose holder keeps writing
 * past the exit waits seconds, not the production grace (design D17 of
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

    FileChannelExecScript getScript() {
        launch.script
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
 * (without the leading binary) and the input source, and answers with a process played from the
 * script — a fresh one per start, as every {@code docker exec} is its own child.
 */
class RecordingLaunch implements ProcessLaunch {

    final List<List<String>> starts = []
    final List<StdinSource> sources = []
    final FileChannelExecScript script = new FileChannelExecScript()
    Process handed = null

    @Override
    Process start(ProcessBuilder builder, StdinSource stdin) {
        starts << builder.command().drop(1)
        sources << stdin
        handed ?: new ScriptedFileChannelProcess(script)
    }
}

/**
 * What every exec the scripted CLI starts does: the bytes it writes to stdout and stderr, the code
 * it exits with, and how it ends — at once (the default); {@link #stalls}, never by itself with its
 * pipes held silent, as a hung in-box command does, until the supervisor kills it; or
 * {@link #exitsOnceStderrRead}, only after a reader has read the stderr it wrote.
 * {@link #interruptsAfterWait} sets the interrupt flag on the thread whose wait returns the exit,
 * scripting an interrupt that lands on the drain joins that follow the wait.
 */
class FileChannelExecScript {

    byte[] stdout = new byte[0]
    String stderr = ''
    int exit = 0
    boolean stalls = false
    boolean exitsOnceStderrRead = false
    boolean interruptsAfterWait = false
}

/**
 * One exec played from a {@link FileChannelExecScript} on {@link FakeProcess}'s own pipes, which
 * behave as Linux pipes (design D6 of make-pipe-reads-non-parking); it replaces no stream.
 *
 * <p>{@link FileChannelExecScript#exitsOnceStderrRead} models the one thing an in-JVM fake
 * otherwise cannot: a real child cannot exit while a pipe nobody reads is full, so a channel that
 * leaves stderr undrained wedges its own wait. The exit rides the reader's own query of stderr
 * ({@link FakePipe#onQuery}): the first query after a read has taken the written bytes, which a
 * reader of what is available takes in one read, ends the process — no thread, no order premise.
 */
class ScriptedFileChannelProcess extends FakeProcess {

    ScriptedFileChannelProcess(FileChannelExecScript script) {
        super(true)
        stdoutPipe().write(script.stdout)
        writeStderr(script.stderr)
        if (script.interruptsAfterWait) {
            onWaitReturn { Thread.currentThread().interrupt() }
        }
        if (script.stalls) {
            holdOpen()
        } else if (script.exitsOnceStderrRead) {
            exitOnceStderrRead(script.exit)
        } else {
            exit(script.exit)
        }
    }

    private void exitOnceStderrRead(int code) {
        FakePipe pipe = stderrPipe()
        Runnable hook = null
        hook = {
            if (pipe.reads() > 0) {
                exit(code)
            } else {
                pipe.onQuery(hook)
            }
        }
        pipe.onQuery(hook)
    }
}
