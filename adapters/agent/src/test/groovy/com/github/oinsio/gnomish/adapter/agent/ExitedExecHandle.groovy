package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.time.Duration
import java.time.Instant
import java.time.InstantSource

/** A process handle that has exited after {@code wallTime}, its stdout waiting in {@code stdout}. */
class ExitedExecHandle implements ExecHandle {

    private final ChildOutput stdout
    private final Duration wallTime

    ExitedExecHandle(ChildOutput stdout, Duration wallTime = Duration.ofSeconds(1)) {
        this.stdout = stdout
        this.wallTime = wallTime
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
    Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
        new Wait.Exited(wallTime)
    }

    @Override
    int waitForExit() {
        0
    }

    @Override
    Duration postExitBound() {
        Duration.ofSeconds(1)
    }
}
