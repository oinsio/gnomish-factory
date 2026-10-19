package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.InstantSource

/**
 * A process handle that, while it "runs", writes its stderr into the file the round named — the
 * stand-in for a CLI whose stderr the launch redirected to the round scratch directory (design D18
 * of fix-terminal-receipt-convergence). The spec resolves that file once the round has launched.
 */
final class ScratchStderrHandle implements ExecHandle {

    private final Closure<Path> stderrFile
    private final ChildOutput stdout
    private final ExecHandle.Wait wait
    private final Duration bound
    private final String stderr

    ScratchStderrHandle(Closure<Path> stderrFile, ChildOutput stdout, ExecHandle.Wait wait, String stderr,
    Duration bound = Duration.ofSeconds(5)) {
        this.stderrFile = stderrFile
        this.stdout = stdout
        this.wait = wait
        this.stderr = stderr
        this.bound = bound
    }

    @Override
    ChildOutput output() {
        stdout
    }

    @Override
    Instant startedAt() {
        Instant.EPOCH
    }

    @Override
    ExecHandle.Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
        Files.writeString(stderrFile(), stderr)
        wait
    }

    @Override
    int waitForExit() {
        0
    }

    @Override
    Duration postExitBound() {
        bound
    }
}
