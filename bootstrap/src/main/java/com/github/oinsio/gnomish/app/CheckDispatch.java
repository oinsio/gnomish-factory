package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.PinCheck;
import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.ExecutorName;

/**
 * The check dispatch of a run (design D18 of migrate-verify-to-executor-contract, as amended
 * 2026-11-10): a check's executor name resolved in the run's {@link ExecutorRegistry}, the entry
 * bound to the check and its working copy at resolution, the bound executor put behind the run's
 * {@link PinCheck} by the entry's describe (design D17: the pin decorator, for a check whose pin set
 * is not empty), and the {@link CheckRound} wrapper built over it as the {@link StageExecutor} the
 * engine's port sees (design D14), handed the bound executor's {@code PendingAdoption} role as the
 * entry bound it — the pin decorator does not take the role, so it is read before the wrapping — and
 * the release of the environment the entry bound it in, which the round runs when its request ends
 * (a program's fresh box per request, design D11 as amended 2026-11-14). It keeps nothing it bound.
 * The engine reaches it through {@link
 * CheckRoundRoute}, for every request that names a check (task 4.2f), so no check runs unpinned.
 *
 * <p>Implements FR1, FR3, FR11, FR13, FR16 of migrate-verify-to-executor-contract.
 */
final class CheckDispatch {

    private final ExecutorRegistry registry;
    private final PinCheck pins;

    /**
     * @param registry the run's executors by name, the same the stage dispatch resolves in; never null
     * @param pins the run's pin-check, over the commit its law was frozen from; never null
     */
    CheckDispatch(ExecutorRegistry registry, PinCheck pins) {
        this.registry = registry;
        this.pins = pins;
    }

    /**
     * The round wrapper of {@code check}, bound to its working copy behind its pin-check.
     *
     * @param check the check to run; never null
     * @param ref the check's identity in the round, which its binding scopes pending runs to; never null
     * @param workspace the check's working copy; never null
     * @return the wrapper the engine's port sees; never null
     * @throws IllegalStateException when the run registered no check binding under the check's
     *     executor — a wiring defect, since the law load refuses an unknown name
     */
    StageExecutor bind(VerifyCheck.Executor check, CheckRef ref, Workspace workspace) {
        var entry = registry.resolve(new ExecutorName(check.executorName()));
        var bound = entry.bound(check, ref, workspace);
        var executor = bound.executor();
        return new CheckRound(
                pins.guarding(executor, check, entry.describe(), workspace),
                CheckRound.adoptionOf(executor),
                check,
                bound.release());
    }
}
