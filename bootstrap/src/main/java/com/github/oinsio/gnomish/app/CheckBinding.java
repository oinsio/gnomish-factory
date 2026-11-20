package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Executor;

/**
 * How a registry entry binds its executor for a check round (design D18 of
 * migrate-verify-to-executor-contract, as amended 2026-11-10): the check it judges and the check's
 * working copy in, a new executor out. A verdict built-in reads the check's declaration and the
 * domain workspace it was bound to, never the request's path, so both are binding arguments, not
 * request fields; nothing bound is kept. The check's identity in the round is a binding argument too:
 * a program's binding scopes the run's table of pending runs to it (design D14 as amended 2026-11-13),
 * and a built-in's binding ignores it.
 *
 * <p>Implements FR1, FR13 of migrate-verify-to-executor-contract.
 */
interface CheckBinding {

    /**
     * The executor of one check round.
     *
     * @param check the check the executor judges; never null
     * @param ref the check's identity in the round; never null
     * @param workspace the check's working copy; never null
     * @return a new executor bound to them; never null
     */
    Executor bind(VerifyCheck.Executor check, CheckRef ref, Workspace workspace);
}
