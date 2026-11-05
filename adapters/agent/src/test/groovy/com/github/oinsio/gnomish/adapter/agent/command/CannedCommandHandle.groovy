package com.github.oinsio.gnomish.adapter.agent.command

import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.time.Duration
import java.time.Instant
import java.time.InstantSource

/**
 * A command's handle over a {@link FakeProcess} that has already printed {@code output} and exited
 * with {@code code}: its wait reports the {@link ExecHandle.Wait} a spec chose and records the bound
 * it was handed, so a timeout or an interruption is shown in virtual time and no order is left to the
 * scheduler — the pipes are closed before the round starts.
 */
final class CannedCommandHandle implements ExecHandle {

    private final FakeProcess process
    private final ChildOutput stdout
    private final ExecHandle.Wait wait
    volatile Duration timeoutSeen

    CannedCommandHandle(int code, ExecHandle.Wait wait, String output = '') {
        this.wait = wait
        this.process = exited(code, output)
        this.stdout = ChildOutput.stdout(process)
    }

    /** A fake process that printed {@code output} and exited with {@code code}, its pipes closed. */
    static FakeProcess exited(int code, String output = '') {
        def process = FakeProcess.running()
        if (!output.isEmpty()) {
            process.writeStdout(output)
        }
        process.exit(code)
        process
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
        timeoutSeen = timeout
        wait
    }

    @Override
    int waitForExit() {
        process.waitFor()
    }

    @Override
    Duration postExitBound() {
        Duration.ofSeconds(2)
    }
}
