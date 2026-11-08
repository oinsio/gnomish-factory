package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.VerdictAnswer;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;

/**
 * The port through which the engine runs one built-in declarative check (e.g.
 * {@code files_exist}, schema validation) without knowing how the check is
 * implemented (design D2): the engine hands over a {@link VerifyCheck.Executor}
 * and the opaque {@link Workspace} it operates on, and reads back a {@link
 * VerdictAnswer}. The check's name and declarative params are the adapter's concern;
 * the verdict is the engine's, derived from the answer by {@code VerdictDerivation}
 * against the check's threshold (D5, D9 of migrate-verify-to-executor-contract).
 *
 * <p>Implements FR3, D2 of add-stage-engine; FR7 of migrate-verify-to-executor-contract.
 */
public interface BuiltinCheckRunner {

    /**
     * Runs the built-in {@code check} against {@code workspace} and returns what it
     * answered — a {@link VerdictAnswer.Sarif} answer of the problems it found, or a
     * {@link VerdictAnswer.InfrastructureFailure} when no answer could be obtained
     * (e.g. malformed params). The engine never inspects the workspace; it belongs to
     * the adapter.
     *
     * <p>Implements FR3, D2 of add-stage-engine; FR7 of migrate-verify-to-executor-contract.
     *
     * @param check the built-in check to run
     * @param workspace the opaque working copy the check operates on
     * @return the check's answer; never null
     */
    VerdictAnswer run(VerifyCheck.Executor check, Workspace workspace);
}
