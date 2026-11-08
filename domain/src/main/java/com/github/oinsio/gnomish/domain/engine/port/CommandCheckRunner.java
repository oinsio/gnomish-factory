package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.VerdictAnswer;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;

/**
 * The port through which the engine runs one command check — an arbitrary
 * executable — without knowing how the command is spawned (design D2): the engine
 * hands over a {@link VerifyCheck.Executor} and the opaque {@link Workspace} it
 * operates on, and reads back a {@link VerdictAnswer}. Turning an exit code and any
 * structured output into an answer is the adapter's concern; the verdict is the
 * engine's, derived from the answer by {@code VerdictDerivation} against the check's
 * threshold (D5, D9 of migrate-verify-to-executor-contract).
 *
 * <p>Implements FR3, D2 of add-stage-engine; FR7 of migrate-verify-to-executor-contract.
 */
public interface CommandCheckRunner {

    /**
     * Runs the {@code check}'s command line against {@code workspace} and returns
     * what it answered — an empty {@link VerdictAnswer.Sarif} answer on exit code 0, a
     * {@link VerdictAnswer.QualityFailure} with findings on a non-zero exit, or a
     * {@link VerdictAnswer.InfrastructureFailure} when no answer could be obtained
     * (e.g. the binary was not found). The engine never inspects the workspace; it
     * belongs to the adapter.
     *
     * <p>Implements FR3, D2 of add-stage-engine; FR7 of migrate-verify-to-executor-contract.
     *
     * @param check the command check to run
     * @param workspace the opaque working copy the command operates on
     * @return the check's answer; never null
     */
    VerdictAnswer run(VerifyCheck.Executor check, Workspace workspace);
}
