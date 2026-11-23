package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor;
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor;
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.adapter.check.PinCheck;
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit;
import com.github.oinsio.gnomish.app.port.git.RunRound;
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces;
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;

/**
 * Binds the {@link StageExecutor} {@link RunAssembler} wires into {@code EnginePorts} — the one
 * executor port, a judge check being one {@code verdict} request on it (design D2 of
 * extract-agent-round-runner) — to the run's execution medium and to the executors the law names. The stage executor
 * is the name-keyed {@link ExecutorDispatch} (design D8 of define-executor-contract): every stage runs
 * the entry its executor block resolves to in the run's {@link ExecutorRegistry} — a {@code program}
 * stage its declaration, a {@code command} stage the built-in, an agent stage the reserved {@code
 * claude} entry over the agent round runner (task 5.1a of extract-agent-round-runner), whose rounds
 * report their progress through the shared renderer {@link ClaudeEntry} subscribes. What this class
 * also decides is where they run: one round source per run, chosen by the medium, opens every round
 * — on the host the host source with the host-git decoration and the dispatch wrapped for the
 * snapshot of a completed git-mode round, in container mode the sandboxed round source; in both
 * branch modes the dispatch is wrapped for resume re-verification.
 *
 * <p>Implements FR7, D6, D10 of add-agent-executor; FR2 of remove-interactive-console; FR6 of
 * make-run-headless; FR9, FR10, FR14, FR21 of define-executor-contract; FR8 of add-command-executor;
 * FR1, FR13, FR19 of migrate-verify-to-executor-contract; FR1, FR4, FR14, M3 of extract-agent-round-runner.
 */
final class ExecutorAdapterSelector {

    private ExecutorAdapterSelector() {}

    /**
     * The run's dispatching stage executor, together with the identity of the rounds its round
     * source opens (FR14, FR21 of define-executor-contract, design D13). One source serves every entry,
     * so the engine stamps the tokens of the very rounds each entry ran: the host construction — its
     * rounds decorated by the host-git decoration, which mints their tokens on a git-mode path and is
     * the identity there; undecorated, the in-place identity — or, in container mode (the integration
     * pass of add-sandbox-core), the sandboxed round source, whose cell is the identity. In both branch
     * modes — the container's, and the git-mode host's through its per-run {@link RunRound} (design
     * D22 of migrate-verify-to-executor-contract) — the dispatch is wrapped by {@link
     * ResumeVerificationStageExecutor}, so an interrupted
     * verification found on resume re-verifies its harvested attempt commit without a re-run (FR21,
     * D15) whichever entry ran the interrupted round — the {@code claude} entry's included, as it did
     * the agent arm's before task 5.1a: the snapshot is the round source's, not the agent's. Every program round is equipped from the run's one {@link
     * ManualRunAssembly#timeEquipment()}, the same the round source's clock comes from, and the
     * registry's {@code command} built-in measures its rounds on that clock too (design D6 of
     * add-command-executor), so a round's wall time and its environment's readings share one clock.
     *
     * <p>The run's {@link ExecutorRegistry} is built here once, at run assembly, from the described law
     * and the built-ins the check equipment hands in typed ({@link CheckEquipment#builtIns}); the stage
     * dispatch and the check dispatch both resolve in it and bind at resolution (design D18 of
     * migrate-verify-to-executor-contract, as amended 2026-11-10); the check dispatch guards every bound
     * executor with the run's pin-check (D17), and {@link StageRounds#port()} routes the engine's check
     * rounds to it outside the stage chain (D18 as amended 2026-11-12).
     *
     * @param assembly the run's assembly; never null
     * @param childEnv the run's child-environment allowlist; never null
     * @param law the run's frozen law; never null
     * @param described what the describe preflight obtained for the law; never null
     * @param definition the law the run is frozen from; never null
     * @param runContext the run's variables an {@code http} check may address its result with
     * @param pins the run's pin-check, which the check dispatch guards every bound executor with
     * @return the stage executor, its rounds' identity and the check dispatch; never null
     */
    static StageRounds stageRounds(
            ManualRunAssembly assembly,
            ChildEnvAllowlist childEnv,
            PipelineLaw law,
            DescribedLaw described,
            PipelineDefinition definition,
            HttpRunContext runContext,
            PinCheck pins) {
        SandboxRunPieces sandbox = assembly.sandbox;
        RunRound hostRun = assembly.hostRun;
        // Sandbox pieces win by construction (design D3 of wire-host-mid-round-push): the host-git
        // decoration is consumed only on the host branch, so a run carrying both seams cannot
        // double-wire its rounds. A git-mode host run carries its round identity with the decoration
        // (design D22 of migrate-verify-to-executor-contract); the in-place run carries neither, and
        // its undecorated source names the in-place identity.
        RoundEnvironmentSource host = CliStageExecutor.hostRounds(assembly.time.clock(), childEnv);
        RoundEnvironmentSource rounds;
        if (sandbox != null) {
            rounds = sandbox.executorRounds();
        } else if (hostRun != null) {
            rounds = assembly.hostGitPush.decorate(host, hostRun.rounds());
        } else {
            rounds = host;
        }
        // The run's round cell under the working-copy check's read-only role (design D21 of
        // migrate-verify-to-executor-contract): a pending commit's move of HEAD is the factory's only
        // when it lands on the cell's last registered commit; the in-place run has no cell.
        LastRegisteredCommit registered =
                sandbox != null ? sandbox.rounds() : hostRun != null ? hostRun.rounds() : LastRegisteredCommit.NONE;
        var equipment = new ProgramRoundEquipment(
                ProgramBounds.DEFAULTS,
                assembly.time,
                childEnv.programGrant(),
                RoundScratchDirectory.HOST_TEMP,
                registered);
        var builtIns =
                assembly.checks.builtIns(childEnv, sandbox, runContext, assembly.time.clock(), equipment.bounds());
        var freshBoxes = sandbox == null ? null : sandbox.freshBoxes();
        // The claude entry (task 4.0a of extract-agent-round-runner): an agent judge check's executor
        // (task 4.1) and every agent stage round's (task 5.1a).
        var claude = ClaudeEntry.of(assembly.factoryProperties, childEnv, law, equipment, freshBoxes);
        var registry = ExecutorRegistry.of(described, definition, law, equipment, builtIns, freshBoxes, claude);
        StageExecutor dispatch = new ExecutorDispatch(registry, rounds);
        StageExecutor executor;
        if (sandbox != null) {
            executor = new ResumeVerificationStageExecutor(dispatch, sandbox.rounds(), sandbox.pendingVerification());
        } else if (hostRun != null) {
            // The host snapshot of a completed round sits outside the dispatch, so every entry is
            // covered, and inside the resume re-verification decorator, so a resumed round is not
            // snapshotted again; the decorator re-verifies the interrupted verification the
            // no-salvage owner found, over the run's one cell (design D22 of
            // migrate-verify-to-executor-contract).
            executor = new ResumeVerificationStageExecutor(
                    assembly.hostGitPush.snapshotting(dispatch, hostRun.rounds()), hostRun.rounds(), hostRun.pending());
        } else {
            executor = dispatch;
        }
        return new StageRounds(executor, rounds.identity(), new CheckDispatch(registry, pins, VerdictInput.of(law)));
    }

    /**
     * A run's stage executor and the identity of the rounds it opens, built together from one round
     * source so the engine stamps the tokens of the very rounds the executor ran, and the check
     * dispatch over the same registry.
     *
     * @param executor the run's stage chain: the dispatch with its decorators; never null
     * @param identity where the engine takes each recorded round's token; never null
     * @param checks the run's check dispatch; never null
     */
    record StageRounds(StageExecutor executor, RoundIdentity identity, CheckDispatch checks) {

        /**
         * The engine's one port over both: a check round to the check dispatch, every other round to
         * the stage chain (design D18 of migrate-verify-to-executor-contract, as amended 2026-11-12) —
         * the route outside the stage chain's decorators, so a check round reaches no stage executor
         * and no resume re-verification.
         *
         * @return the port the run's {@code EnginePorts} carries; never null
         */
        StageExecutor port() {
            return new CheckRoundRoute(executor, checks);
        }
    }
}
