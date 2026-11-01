package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.agent.CliJudgeVoter;
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor;
import com.github.oinsio.gnomish.adapter.agent.LoggingAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor;
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import java.time.InstantSource;
import org.jspecify.annotations.Nullable;

/**
 * Binds the {@link StageExecutor}/{@link JudgeVoter} pair {@link RunAssembler} wires into {@code
 * EnginePorts} to the run's execution medium and to the executors the law names. The stage executor
 * is the name-keyed {@link ExecutorDispatch} (design D8 of define-executor-contract): a {@code
 * program} stage runs the executor its name resolves to in the run's {@link ExecutorRegistry}, every
 * other stage the manifest-driven CLI adapter, the one type-keyed arm (FR2 of
 * remove-interactive-console). What this class also decides is where they run: one round source per
 * run, chosen by the medium, opens the rounds of both arms — on the host the host source with the
 * host-git decoration, in container mode the sandboxed round source, with the dispatch wrapped for
 * resume re-verification — and the judge votes in the sandbox's judge environments. The CLI adapters
 * report their rounds' progress through the shared {@link LoggingAgentProgressListener} renderer.
 *
 * <p>Implements FR7, D6, D10 of add-agent-executor; FR2 of remove-interactive-console; FR6 of
 * make-run-headless; FR9, FR10, FR14, FR21 of define-executor-contract.
 */
final class ExecutorAdapterSelector {

    private ExecutorAdapterSelector() {}

    /**
     * The run's dispatching stage executor, together with the identity of the rounds its round
     * source opens (FR14, FR21 of define-executor-contract, design D13). One source serves both arms,
     * so the engine stamps the tokens of the very rounds either arm ran: the host construction — its
     * rounds decorated by the host-git decoration, which mints their tokens on a git-mode path and is
     * the identity there; undecorated, the in-place identity — or, in container mode (the integration
     * pass of add-sandbox-core), the sandboxed round source, whose cell is the identity. In container
     * mode the dispatch is wrapped by {@link ResumeVerificationStageExecutor}, so an interrupted
     * verification found on resume re-verifies its harvested attempt commit without a re-run (FR21,
     * D15) whichever arm ran the interrupted round: the snapshot is the round source's, not the
     * agent's. Every program round is equipped from the run's one {@link
     * ManualRunAssembly#timeEquipment()}, the same the round source's clock comes from, so a round's
     * wall time and its environment's readings share one clock.
     *
     * @param assembly the run's assembly; never null
     * @param childEnv the run's child-environment allowlist; never null
     * @param law the run's frozen law; never null
     * @param described what the describe preflight obtained for the law; never null
     * @param definition the law the run is frozen from; never null
     * @return the stage executor and its rounds' identity; never null
     */
    static StageRounds stageRounds(
            ManualRunAssembly assembly,
            ChildEnvAllowlist childEnv,
            PipelineLaw law,
            DescribedLaw described,
            PipelineDefinition definition) {
        SandboxRunPieces sandbox = assembly.sandbox;
        // Sandbox pieces win by construction (design D3 of wire-host-mid-round-push): the host-git
        // decoration is consumed only on the host branch, so a run carrying both seams cannot
        // double-wire its rounds. The decoration defaults to identity, so applying it unconditionally
        // IS the previous host construction when nothing was attached — the in-place run, whose
        // undecorated source names the in-place identity.
        RoundEnvironmentSource rounds = sandbox == null
                ? assembly.hostGitPush.apply(CliStageExecutor.hostRounds(assembly.time.clock(), childEnv))
                : sandbox.executorRounds();
        var cli = new CliStageExecutor(
                assembly.factoryProperties,
                assembly.time.clock(),
                new LoggingAgentProgressListener(),
                childEnv,
                law,
                rounds);
        var equipment = new ProgramRoundEquipment(
                ProgramBounds.DEFAULTS, assembly.time, childEnv.programGrant(), RoundScratchDirectory.HOST_TEMP);
        StageExecutor dispatch =
                new ExecutorDispatch(ExecutorRegistry.of(described, definition, law, rounds, equipment), cli);
        StageExecutor executor = sandbox == null
                ? dispatch
                : new ResumeVerificationStageExecutor(dispatch, sandbox.rounds(), sandbox.pendingVerification());
        return new StageRounds(executor, rounds.identity());
    }

    /**
     * A run's stage executor and the identity of the rounds it opens, built together from one round
     * source so the engine stamps the tokens of the very rounds the executor ran.
     *
     * @param executor the run's stage executor; never null
     * @param identity where the engine takes each recorded round's token; never null
     */
    record StageRounds(StageExecutor executor, RoundIdentity identity) {}

    /**
     * The manifest-driven CLI judge voter, wired with the shared renderer (task 9.4, design D10).
     */
    static JudgeVoter judgeVoter(
            FactoryProperties factoryProperties,
            InstantSource instantSource,
            ChildEnvAllowlist childEnv,
            PipelineLaw law,
            @Nullable SandboxRunPieces sandbox) {
        return new CliJudgeVoter(
                factoryProperties,
                instantSource,
                new LoggingAgentProgressListener(),
                childEnv,
                law,
                sandbox == null ? null : sandbox.judgeEnvironments());
    }
}
