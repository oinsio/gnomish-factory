package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;

/**
 * The real CLI {@link StageExecutor} adapter (task 6.5 of add-agent-executor):
 * one fresh {@code claude -p} subprocess per {@link #execute} call, assembling
 * every collaborator built by tasks 4–6 — {@link ExecutorPromptBuilder} for
 * the round prompt, a {@link RoundEnvironmentSource} for the round's execution
 * environment and decision transport (host default: worktree + temp-dir
 * decision file; sandboxed: the leased container environment with the
 * in-branch decision file and snapshot-closed rounds — FR4, FR21, FR23 of
 * add-sandbox-core), {@link AgentInvocationOptions#renderForExecutor} for the
 * invocation flags (including the pinpoint decision-file {@code Write}
 * allowance), {@link StreamJsonParser} to read its stream-json stdout, {@link
 * AgentRoundResultExtractor} to shape the essential result, and {@link
 * DecisionFileReader} to interpret the decision file's raw content.
 *
 * <p>Infrastructure failures — an unreadable control/criteria file ({@link
 * UnreadableLawFileException}), a process that
 * cannot even start, a {@code roundTimeout} expiry ({@link
 * RoundTimeoutException}), a missing result event ({@link
 * MissingResultEventException}) — all propagate uncaught: {@code
 * RoundExecution#execute} catches any {@link RuntimeException} this port
 * throws and shapes it into {@code RoundOutcome.CannotExecute} without
 * burning a stage attempt (NFR-R1). Both {@link ExecutionResult.Completed}
 * and {@link ExecutionResult.DecisionNeeded} carry the exact same {@code
 * usage}/{@code trace} pair — telemetry is collected from the stream
 * regardless of the round's outcome (FR3).
 *
 * <p>The {@link AgentProgressListener} supplied at construction (task 9.4, design D10) is
 * threaded straight into {@link StreamJsonParser}, so every recognized event on this
 * executor's rounds reaches it live, on the parse loop's own thread (FR7, NFR-O1, UX1);
 * the round source may add a per-round listener (the sandboxed mid-round harvest poll, FR5).
 *
 * <p>Implements FR1, FR3, FR4, FR6, FR7, FR13, D1, D2, D3, D9, D10 of add-agent-executor.
 */
public final class CliStageExecutor implements StageExecutor {

    private final FactoryProperties factoryProperties;
    private final Clock clock;
    private final AgentProgressListener progressListener;
    private final ExecutorPromptBuilder promptBuilder;
    private final RoundEnvironmentSource environmentSource;
    private final AgentRoundResultExtractor resultExtractor = new AgentRoundResultExtractor();
    private final DecisionFileReader decisionFileReader = new DecisionFileReader();

    /**
     * No-op listener. See the canonical constructor {@link #CliStageExecutor(FactoryProperties,
     * Clock, AgentProgressListener, PipelineLaw, RoundEnvironmentSource)} for the full parameter
     * contract.
     */
    public CliStageExecutor(FactoryProperties factoryProperties, Clock clock, PipelineLaw law) {
        this(factoryProperties, clock, _ -> {}, law);
    }

    /**
     * {@link ChildEnvAllowlist#none()}. See the canonical constructor {@link #CliStageExecutor(
     * FactoryProperties, Clock, AgentProgressListener, PipelineLaw, RoundEnvironmentSource)} for
     * the full parameter contract.
     */
    public CliStageExecutor(
            FactoryProperties factoryProperties, Clock clock, AgentProgressListener progressListener, PipelineLaw law) {
        this(factoryProperties, clock, progressListener, ChildEnvAllowlist.none(), law);
    }

    /**
     * Host default (a {@link HostRoundEnvironmentSource}). See the canonical constructor {@link
     * #CliStageExecutor(FactoryProperties, Clock, AgentProgressListener, PipelineLaw,
     * RoundEnvironmentSource)} for the full parameter contract.
     */
    public CliStageExecutor(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            ChildEnvAllowlist childEnv,
            PipelineLaw law) {
        this(factoryProperties, clock, progressListener, new DecisionFileTransport(), childEnv, law);
    }

    /**
     * The canonical constructor. The sandboxed construction (the integration pass of
     * add-sandbox-core): rounds run through {@code environmentSource} — the leased container
     * environment with the in-branch decision file and snapshot-closed rounds — instead of the
     * host default.
     *
     * @param factoryProperties installation config: the CLI binary path; never null
     * @param clock the read-time source for process start/exit stamping; never null
     * @param progressListener the live-progress subscriber for this executor's rounds; never null
     * @param law the invocation's frozen pipeline law (D14 of add-sandbox-core); never null
     * @param environmentSource where each round's environment and decision transport come from;
     *     never null
     */
    public CliStageExecutor(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            PipelineLaw law,
            RoundEnvironmentSource environmentSource) {
        this.factoryProperties = factoryProperties;
        this.clock = clock;
        this.progressListener = progressListener;
        this.promptBuilder = new ExecutorPromptBuilder(law);
        this.environmentSource = environmentSource;
    }

    /**
     * Testing seam (package-private): the same executor with the per-round
     * decision-file transport supplied by the caller, so a spec can assert the
     * infrastructure-failure cleanup contract — {@code runRound} must {@link
     * DecisionFileTransport.Handle#discard()} the round directory on any {@link
     * RuntimeException} (NFR-R3, D1) — without depending on the shared JVM temp
     * directory. Production always uses the no-arg transport.
     */
    CliStageExecutor(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            DecisionFileTransport decisionFileTransport,
            PipelineLaw law) {
        this(factoryProperties, clock, progressListener, decisionFileTransport, ChildEnvAllowlist.none(), law);
    }

    /** Testing seam (package-private): same as the four-argument overload, plus the allowlist. */
    CliStageExecutor(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            DecisionFileTransport decisionFileTransport,
            ChildEnvAllowlist childEnv,
            PipelineLaw law) {
        this(factoryProperties, clock, progressListener, law, hostRounds(decisionFileTransport, clock, childEnv));
    }

    /**
     * The host-mode {@link RoundEnvironmentSource} the host convenience constructor wires —
     * exposed so {@code bootstrap} can decorate it (the git-mode mid-round push) and hand the
     * decorated source back through the canonical rounds-accepting constructor, keeping {@code
     * HostRoundEnvironmentSource} and its {@link DecisionFileTransport} internals package-private
     * (design D2 of wire-host-mid-round-push).
     *
     * <p>Implements FR2 of wire-host-mid-round-push.
     *
     * @param clock the exec start-instant source; never null
     * @param childEnv the run's layered child-env allowlist; never null
     * @return the host round source, identical to the host constructor's; never null
     */
    public static RoundEnvironmentSource hostRounds(Clock clock, ChildEnvAllowlist childEnv) {
        return hostRounds(new DecisionFileTransport(), clock, childEnv);
    }

    /** Testing seam (package-private): {@link #hostRounds} with the transport supplied. */
    static RoundEnvironmentSource hostRounds(DecisionFileTransport transport, Clock clock, ChildEnvAllowlist childEnv) {
        return new HostRoundEnvironmentSource(transport, clock, childEnv);
    }

    /**
     * Runs one fresh CLI round for {@code request} (FR1, D2): builds the
     * prompt, opens the round's environment and decision transport, launches
     * the process, parses its stream-json output, waits for exit within {@code
     * roundTimeout}, closes the round (the sandboxed snapshot commit, FR21),
     * extracts the round's essential result, and maps the decision file's
     * presence to {@link ExecutionResult.Completed} or
     * {@link ExecutionResult.DecisionNeeded}.
     *
     * <p>Implements FR1, FR3, FR4, FR13, D1, D2, D3, D9 of add-agent-executor.
     *
     * @param request the round's inputs
     * @return the round's outcome, carrying shared telemetry regardless of
     *     variant; never null
     * @throws UnreadableLawFileException if the control file or a judge criteria
     *     file was unreadable when the invocation's law was frozen (FR13, D14)
     * @throws RoundTimeoutException if {@code roundTimeout} expires (FR13)
     * @throws MissingResultEventException if no result event was emitted (FR4)
     */
    @Override
    public ExecutionResult execute(Request request) {
        String prompt = promptBuilder.build(request);
        RoundEnvironmentSource.Round round = environmentSource.openRound(request);
        return runRound(request, prompt, round);
    }

    private ExecutionResult runRound(Request request, String prompt, RoundEnvironmentSource.Round round) {
        try {
            return runRoundInEnvironment(request, prompt, round);
        } catch (RuntimeException e) {
            round.discard();
            throw e;
        }
    }

    private ExecutionResult runRoundInEnvironment(Request request, String prompt, RoundEnvironmentSource.Round round) {
        return ExecutorRoundExecution.run(
                factoryProperties,
                clock,
                progressListener,
                resultExtractor,
                decisionFileReader,
                request,
                prompt,
                round);
    }
}
