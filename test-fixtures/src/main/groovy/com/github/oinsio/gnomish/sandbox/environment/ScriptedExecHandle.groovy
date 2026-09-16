package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.sandbox.ExecHandle

import java.time.Duration
import java.time.Instant

/**
 * One scripted in-box probe answer, in the shape the self-check captures it:
 * a fixed exit code and stdout, an {@link Instant#EPOCH} start time, and a
 * {@code waitForExitOrTimeout} that refuses to run because the self-check
 * never calls it. Shared between {@code EnvironmentSelfCheckSpec} and
 * {@code SelfCheckedEnvironmentSpec}, which both fake a probe's {@link ExecHandle}
 * the same way.
 */
final class ScriptedExecHandle {

    private ScriptedExecHandle() {
    }

    static ExecHandle of(int code, String out) {
        new ExecHandle() {

                    @Override
                    InputStream output() {
                        new ByteArrayInputStream(out.getBytes('UTF-8'))
                    }

                    @Override
                    Instant startedAt() {
                        Instant.EPOCH
                    }

                    @Override
                    ExecHandle.Wait waitForExitOrTimeout(Duration timeout, Clock clock) {
                        throw new UnsupportedOperationException('not used by the self-check')
                    }

                    @Override
                    int waitForExit() {
                        code
                    }
                }
    }
}
