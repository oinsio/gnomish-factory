package com.github.oinsio.gnomish.app.port.check;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;

/**
 * Where a {@link ShellCommandCheckRunner} check gets its execution environment
 * (the sandbox integration pass of add-sandbox-core): the host default
 * materializes a {@code HostTaskExecutionEnvironment} over the {@code
 * DirectoryWorkspace} root per check — today's behavior; the sandboxed source
 * hands out the round's leased environment for {@code verify-in: same-box}
 * checks and a fresh environment materialized from the attempt commit for
 * {@code verify-in: fresh-box} (FR13).
 *
 * <p>Implements FR4, FR13 of add-sandbox-core.
 */
public interface CheckEnvironmentSource {

    /**
     * Acquires the environment {@code check} runs in.
     *
     * @param check the command check about to run, carrying its freshness knob
     * @param workspace the engine's workspace for the round under verification
     * @return the acquired environment and its release action; never null
     * @throws CheckEnvironmentUnavailableException if no environment can serve the check — an
     *     infrastructure failure the runner maps to {@code CannotVerify}
     */
    Acquired acquire(VerifyCheck.Command check, Workspace workspace);

    /** One check's environment and its release action ({@code close} runs in a finally). */
    interface Acquired extends AutoCloseable {

        /** The environment the check's process executes in (FR4). */
        TaskExecutionEnvironment environment();

        /** Releases the environment: disposal for per-check environments, a no-op for leased ones. */
        @Override
        void close();
    }
}
