package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.Objects;

/**
 * The engine's one {@link StageExecutor} port as a run's assembly presents it (design D18 of
 * migrate-verify-to-executor-contract, as amended 2026-11-12): a request that names a check as its
 * subject is a check round, and goes to the run's {@link CheckDispatch} — the check's registry entry
 * bound to the check and the request's working copy, behind the pin decorator, wrapped as a {@link
 * CheckRound} — and every other request is a stage round, and goes to the run's stage chain. The
 * route sits outside the stage chain, so a check round reaches no stage executor, no snapshot and no
 * resume re-verification, and the engine still sees one port (proposal G1). The check's identity in
 * the round, which the request carries with the check, goes to the binding with it (design D14 as
 * amended 2026-11-13).
 *
 * <p>Implements FR1, FR2, FR13 of migrate-verify-to-executor-contract.
 */
final class CheckRoundRoute implements StageExecutor {

    private final StageExecutor stages;
    private final CheckDispatch checks;

    /**
     * @param stages the run's stage chain: the dispatch with its decorators; never null
     * @param checks the run's check dispatch; never null
     */
    CheckRoundRoute(StageExecutor stages, CheckDispatch checks) {
        this.stages = Objects.requireNonNull(stages, "stages");
        this.checks = Objects.requireNonNull(checks, "checks");
    }

    @Override
    public ExecutionResult execute(Request request) {
        VerifyCheck.Executor check = request.check();
        return check == null
                ? stages.execute(request)
                : checks.bind(check, Objects.requireNonNull(request.checkRef(), "checkRef"), request.workspace())
                        .execute(request);
    }
}
