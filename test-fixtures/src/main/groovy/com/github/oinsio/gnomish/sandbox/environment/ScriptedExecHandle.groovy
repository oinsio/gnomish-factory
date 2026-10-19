package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.time.Duration
import java.time.Instant
import java.time.InstantSource

/**
 * One scripted in-box probe answer, in the shape the self-check captures it:
 * a fixed exit code and stdout, an {@link Instant#EPOCH} start time, and a
 * {@code waitForExitOrTimeout} that refuses to run because the self-check
 * never calls it. Shared between {@code EnvironmentSelfCheckSpec} and
 * {@code SelfCheckedEnvironmentSpec}, which both fake a probe's {@link ExecHandle}
 * the same way. The stdout is a {@link FakeProcess}'s that wrote {@code out} and exited with
 * {@code code}, minted as its {@link ChildOutput} (design D6 of make-pipe-reads-non-parking).
 */
final class ScriptedExecHandle {

    private ScriptedExecHandle() {
    }

    static ExecHandle of(int code, String out) {
        def child = FakeProcess.running()
        child.writeStdout(out)
        child.exit(code)
        def stdout = ChildOutput.stdout(child)
        new ExecHandle() {

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
                        throw new UnsupportedOperationException('not used by the self-check')
                    }

                    @Override
                    int waitForExit() {
                        code
                    }

                    @Override
                    Duration postExitBound() {
                        Duration.ofSeconds(5)
                    }
                }
    }
}
