package com.github.oinsio.gnomish.adapter.scm.builtin;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;

/**
 * One credentialed SCM built-in as the executor registry holds it (design D18 of
 * migrate-verify-to-executor-contract, as amended 2026-11-10): its describe answer and its
 * long-lived collaborator — the GitHub core over the connection run assembly admitted, carrying its
 * token — built once per run, with the executor bound at resolution to the check it judges and the
 * check's working copy. Nothing bound is kept: each binding is a new executor.
 *
 * <p>Implements FR1, FR13 of migrate-verify-to-executor-contract.
 */
public interface ScmBuiltIn {

    /**
     * The built-in's describe answer, the same for every binding.
     *
     * @return the answer; never null
     */
    Describe describe();

    /**
     * The executor of one check round.
     *
     * @param check the check the executor judges; never null
     * @param workspace the check's working copy, carrying the attempt commit; never null
     * @return a new executor bound to both; never null
     */
    Executor bind(VerifyCheck.Executor check, Workspace workspace);
}
