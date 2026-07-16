package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;

/**
 * The port through which the engine runs one built-in declarative check (e.g.
 * {@code files_exist}, schema validation) without knowing how the check is
 * implemented (design D2): the engine hands over a {@link VerifyCheck.Builtin}
 * and the opaque {@link Workspace} it operates on, and reads back a {@link
 * Verdict}. The check's name and declarative params are the adapter's concern;
 * the engine only asks for a verdict.
 *
 * <p>Implements FR3, D2 of add-stage-engine.
 */
public interface BuiltinCheckRunner {

    /**
     * Runs the built-in {@code check} against {@code workspace} and returns its
     * verdict — {@link Verdict.Pass} when the check passed, {@link Verdict.Fail}
     * with findings on a quality failure, or {@link Verdict.CannotVerify} when no
     * verdict could be obtained (e.g. an unknown check name). The engine never
     * inspects the workspace; it belongs to the adapter.
     *
     * <p>Implements FR3, D2 of add-stage-engine.
     *
     * @param check the built-in check to run
     * @param workspace the opaque working copy the check operates on
     * @return the verdict of the check; never null
     */
    Verdict run(VerifyCheck.Builtin check, Workspace workspace);
}
