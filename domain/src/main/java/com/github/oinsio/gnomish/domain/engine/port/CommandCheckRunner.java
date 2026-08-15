package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;

/**
 * The port through which the engine runs one command check — an arbitrary
 * executable whose contract is "exit code 0 = pass" — without knowing how the
 * command is spawned (design D2): the engine hands over a {@link
 * VerifyCheck.Command} and the opaque {@link Workspace} it operates on, and reads
 * back a {@link Verdict}. Turning an exit code and any structured output into a
 * verdict is the adapter's concern; the engine only asks for a verdict.
 *
 * <p>Implements FR3, D2 of add-stage-engine.
 */
public interface CommandCheckRunner {

    /**
     * Runs the {@code check}'s command line against {@code workspace} and returns
     * its verdict — {@link Verdict.Pass} on exit code 0, {@link Verdict.Fail} with
     * findings on a non-zero exit (a quality failure), or {@link
     * Verdict.CannotVerify} when no verdict could be obtained (e.g. the binary was
     * not found). The engine never inspects the workspace; it belongs to the
     * adapter.
     *
     * <p>Implements FR3, D2 of add-stage-engine.
     *
     * @param check the command check to run
     * @param workspace the opaque working copy the command operates on
     * @return the verdict of the check; never null
     */
    Verdict run(VerifyCheck.Command check, Workspace workspace);
}
