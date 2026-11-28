package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.program.PendingTurnRun;
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
 * <p>The port's operation selects the arm (D14), through {@link PendingTurnRun}, the one
 * choice a stage round makes too: {@link #execute} → {@code start}; {@link #poll} with a key this run
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
 * <p>Implements FR1, FR2, FR3, FR11, FR13, NFR-R1 of migrate-verify-to-executor-contract; FR4 of
 * extract-agent-round-runner.
 */
final class CheckRound implements StageExecutor {

    /** The working copy as a check round names it: the built-in reads the one it was bound to. */
    private static final String WORKSPACE = ".";

    private final String executor;
    private final Executor bound;
    private final @Nullable PendingAdoption adoption;
    private final VerifyCheck.Executor check;
    private final Runnable release;
    private final VerdictInput input;

    /**
     * A round over an executor bound in no environment of its own, of a run with no law: it releases
     * nothing, and names the check by its identifier.
     *
     * @param bound the executor bound to {@code check} and its working copy, behind its pin-check;
     *     never null
     * @param adoption the same executor's adoption role, as its binding built it before any decorator;
     *     {@code null} for an executor that does not take the role
     * @param check the check the round judges; never null
     */
    CheckRound(Executor bound, @Nullable PendingAdoption adoption, VerifyCheck.Executor check) {
        this(bound, adoption, check, () -> {}, VerdictInput.IDENTIFIER_ONLY);
    }

    /**
     * @param bound the executor bound to {@code check} and its working copy, behind its pin-check;
     *     never null
     * @param adoption the same executor's adoption role, as its binding built it before any decorator;
     *     {@code null} for an executor that does not take the role
     * @param check the check the round judges; never null
     * @param release what releases the environment the executor was bound in, run once when the
     *     request ends, whatever it answered or threw; never null
     * @param input the run's role input of a check's request; never null
     */
    CheckRound(
            Executor bound,
            @Nullable PendingAdoption adoption,
            VerifyCheck.Executor check,
            Runnable release,
            VerdictInput input) {
        this.executor = check.executorName();
        this.bound = bound;
        this.adoption = adoption;
        this.check = check;
        this.release = release;
        this.input = input;
    }

    /**
     * The round of a check refused ahead of its request — a judge whose criteria cannot be read: it
     * binds nothing, asks no executor, and answers {@code refusal} through the one mapping every
     * check round's answer takes.
     *
     * @param check the check; never null
     * @param refusal the check's answer; never null
     * @return the round; never null
     */
    static StageExecutor refused(VerifyCheck.Executor check, ExecutorResult refusal) {
        return new StageExecutor() {
            @Override
            public ExecutionResult execute(Request request) {
                return engineResult(check.executorName(), request, refusal);
            }

            @Override
            public ExecutionResult poll(Request request, PendingTurn turn) {
                return engineResult(check.executorName(), request, refusal);
            }
        };
    }

    @Override
    public ExecutionResult execute(Request request) {
        return run(request, null);
    }

    /**
     * A check's poll is a check round of its own, as its {@code start} is: the request binds a fresh
     * environment and releases it when it ends (design D14 of migrate-verify-to-executor-contract;
     * D11 of extract-agent-round-runner, as amended 2026-11-25).
     */
    @Override
    public ExecutionResult poll(Request request, PendingTurn turn) {
        return run(request, turn);
    }

    private ExecutionResult run(Request request, @Nullable PendingTurn turn) {
        ExecutorResult result;
        try {
            var verdict = new ExecutorRequest(
                    Role.VERDICT,
                    new Workspace(WORKSPACE),
                    new Policy.ReadOnly(),
                    RoleInputs.verdict(input.text(check, request.context())),
                    ExecutorContractJson.settingsTree(check.settings()),
                    Bounds.none(),
                    List.of(),
                    null);
            result = PendingTurnRun.run(bound, adoption, verdict, turn);
        } finally {
            // A box lives for this one request: its start, or its adoption and poll.
            release.run();
        }
        return engineResult(executor, request, result);
    }

    private static ExecutionResult engineResult(String executor, Request request, ExecutorResult result) {
        var key = new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
        return ProgramResultMapping.toEngine(executor, Role.VERDICT, key, result, List.of());
    }
}
