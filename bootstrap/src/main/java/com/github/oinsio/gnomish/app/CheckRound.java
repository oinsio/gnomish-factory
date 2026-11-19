package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping;
import com.github.oinsio.gnomish.app.port.check.PendingAdoption;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Bounds;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Policy;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.RoleInputs;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The application-side wrapper of one check round (design D14, D18 of
 * migrate-verify-to-executor-contract): the {@link StageExecutor} the engine's port sees, built at
 * resolution over the executor bound to the check and its working copy. Each {@link #execute} sends
 * the check a {@code verdict} request — read-only, the check's settings, the working copy as the
 * built-in names it — and maps the contract result through the one mapping of a contract result onto
 * the engine's shape ({@link ProgramResultMapping}), in role {@code verdict}, so a verdict answer
 * crosses the port as an {@code ExecutionResult.VerdictReply}.
 *
 * <p>The request's {@link PendingTurn} selects the arm (D14): no turn → {@code start}; a key this run
 * issued ({@link PendingTurn.Origin#ISSUED}) → {@code poll} by that key, handed over as the turn
 * carries it: the turn and the contract name one type, the {@code :pendingkey} leaf's (D20), so
 * nothing is re-wrapped here. A recorded key ({@link PendingTurn.Origin#RECORDED}, set by the resume
 * path only) is first handed to the executor's {@link PendingAdoption}, when the bound executor takes
 * that role (a program: its keys live in the run's table of its registry entry), and then polled;
 * an executor whose key every instance can poll (the built-ins) takes no role, and the recorded key is polled as it is.
 * The adoption is the only way a recorded key reaches an executor's own pending runs; it and the poll
 * that follows run in the one environment the request was bound in, which the round releases when
 * the request ends (a program's fresh box per request, design D11 as amended 2026-11-14). Every check
 * round of a run reaches this wrapper through {@link CheckRoundRoute} and {@link CheckDispatch} (task
 * 4.2f).
 *
 * <p>Implements FR1, FR2, FR3, FR11, FR13, NFR-R1 of migrate-verify-to-executor-contract.
 */
final class CheckRound implements StageExecutor {

    /** The working copy as a check round names it: the built-in reads the one it was bound to. */
    private static final String WORKSPACE = ".";

    private final String executor;
    private final Executor bound;
    private final @Nullable PendingAdoption adoption;
    private final VerifyCheck.Executor check;
    private final Runnable release;

    /**
     * A round over an executor bound in no environment of its own: it releases nothing.
     *
     * @param bound the executor bound to {@code check} and its working copy, behind its pin-check;
     *     never null
     * @param adoption the same executor's adoption role, taken before the pin decorator wrapped it;
     *     {@code null} for an executor that does not take the role
     * @param check the check the round judges; never null
     */
    CheckRound(Executor bound, @Nullable PendingAdoption adoption, VerifyCheck.Executor check) {
        this(bound, adoption, check, () -> {});
    }

    /**
     * @param bound the executor bound to {@code check} and its working copy, behind its pin-check;
     *     never null
     * @param adoption the same executor's adoption role, taken before the pin decorator wrapped it;
     *     {@code null} for an executor that does not take the role
     * @param check the check the round judges; never null
     * @param release what releases the environment the executor was bound in, run once when the
     *     request ends, whatever it answered or threw; never null
     */
    CheckRound(Executor bound, @Nullable PendingAdoption adoption, VerifyCheck.Executor check, Runnable release) {
        this.executor = check.executorName();
        this.bound = bound;
        this.adoption = adoption;
        this.check = check;
        this.release = release;
    }

    /**
     * The adoption role of an executor as its entry bound it, read before the pin decorator wraps it.
     *
     * @param executor the executor the entry bound; never null
     * @return its {@link PendingAdoption} role, or {@code null} when it does not take the role
     */
    static @Nullable PendingAdoption adoptionOf(Executor executor) {
        return executor instanceof PendingAdoption adoption ? adoption : null;
    }

    @Override
    public ExecutionResult execute(Request request) {
        var key = new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
        var verdict = new ExecutorRequest(
                Role.VERDICT,
                new Workspace(WORKSPACE),
                new Policy.ReadOnly(),
                RoleInputs.verdict("check '" + check.checkId() + "'"),
                ExecutorContractJson.settingsTree(check.settings()),
                Bounds.none(),
                List.of(),
                null);
        try {
            return ProgramResultMapping.toEngine(
                    executor, verdict.role(), key, run(verdict, request.pending()), List.of());
        } finally {
            // A program's box lives for this one request: its start, or its adoption and poll.
            release.run();
        }
    }

    private ExecutorResult run(ExecutorRequest verdict, @Nullable PendingTurn turn) {
        if (turn == null) {
            return bound.start(verdict);
        }
        if (turn.origin() == PendingTurn.Origin.RECORDED && adoption != null) {
            adoption.adopt(verdict, turn.key());
        }
        return bound.poll(verdict, turn.key());
    }
}
