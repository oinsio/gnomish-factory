package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.agent.JudgeEnvironmentSource;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The real CLI {@link JudgeVoter} adapter (task 7.5 of add-agent-executor):
 * one fresh {@code claude -p} subprocess per {@link #vote} call, assembling
 * {@link JudgeCriteriaPreflight} for the criteria-readability precheck,
 * {@link JudgePromptBuilder} for the round prompt, {@link
 * AgentInvocationOptions#renderForJudge} for the hard-wired read-only
 * invocation flags, {@link HostTaskExecutionEnvironment#exec} to run the process
 * through the task environment port (never a direct spawn — FR4 of
 * add-sandbox-core), {@link
 * StreamJsonParser} to read its stream-json stdout, {@link
 * AgentRoundResultExtractor} to shape the essential result, and {@link
 * JudgeVerdictExtractor} to interpret the round's final message.
 *
 * <p>Unlike {@link CliStageExecutor}, this port never throws (design D5,
 * NFR-R1): the judge's degradation direction is inverted from the
 * executor's, since the judge IS the QC net — an unreadable criteria file, a
 * process that cannot start, a {@code roundTimeout} expiry, or a missing
 * result event are all mapped to a normal {@link JudgeVoter.Vote} carrying
 * {@link Verdict.CannotVerify}, never an uncaught exception. {@link
 * JudgeCriteriaPreflight#checkReadable} runs first, before any prompt is
 * built or process spawned (FR13): "never a criteria-less vote".
 *
 * <p>The {@link AgentProgressListener} supplied at construction (task 9.4, design D10) is
 * threaded straight into {@link StreamJsonParser}: judge rounds feed the same live-progress
 * stream as executor rounds, indistinguishable in shape — the run assembly is expected to wire
 * the shared {@link LoggingAgentProgressListener} renderer alone here, never the executor-only
 * status enricher (FR7, D10).
 *
 * <p>Implements FR7, FR8, FR9, FR12, FR13, D5, D7, D10 of add-agent-executor; FR15, FR19, D9,
 * D14 of add-sandbox-core; cross-references NFR-R1 of add-stage-engine.
 */
public final class CliJudgeVoter implements JudgeVoter {

    private final FactoryProperties factoryProperties;
    private final Clock clock;
    private final AgentProgressListener progressListener;
    private final PipelineLaw law;
    private final JudgePromptBuilder promptBuilder;
    private final JudgeEnvironmentSource environmentSource;
    private final AgentRoundResultExtractor resultExtractor = new AgentRoundResultExtractor();
    private final JudgeVerdictExtractor verdictExtractor = new JudgeVerdictExtractor();

    /**
     * No-op listener, {@link ChildEnvAllowlist#none()}, host environment. See the canonical
     * constructor {@link #CliJudgeVoter(FactoryProperties, Clock, AgentProgressListener,
     * ChildEnvAllowlist, PipelineLaw, JudgeEnvironmentSource)} for the full parameter contract.
     */
    public CliJudgeVoter(FactoryProperties factoryProperties, Clock clock, PipelineLaw law) {
        this(factoryProperties, clock, _ -> {}, law);
    }

    /**
     * {@link ChildEnvAllowlist#none()}, host environment. See the canonical constructor {@link
     * #CliJudgeVoter(FactoryProperties, Clock, AgentProgressListener, ChildEnvAllowlist,
     * PipelineLaw, JudgeEnvironmentSource)} for the full parameter contract.
     */
    public CliJudgeVoter(
            FactoryProperties factoryProperties, Clock clock, AgentProgressListener progressListener, PipelineLaw law) {
        this(factoryProperties, clock, progressListener, ChildEnvAllowlist.none(), law);
    }

    /**
     * Host environment (a {@link HostTaskExecutionEnvironment} over the graded {@link
     * DirectoryWorkspace}'s root). See the canonical constructor {@link #CliJudgeVoter(
     * FactoryProperties, Clock, AgentProgressListener, ChildEnvAllowlist, PipelineLaw,
     * JudgeEnvironmentSource)} for the full parameter contract.
     */
    public CliJudgeVoter(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            ChildEnvAllowlist childEnv,
            PipelineLaw law) {
        this(factoryProperties, clock, progressListener, childEnv, law, null);
    }

    /**
     * The canonical constructor.
     *
     * @param factoryProperties installation config: the CLI binary path; never null
     * @param clock the read-time source for process start/exit stamping; never null
     * @param progressListener the live-progress subscriber for this judge's rounds (design D10,
     *     task 9.4); judge rounds feed the same {@link LoggingAgentProgressListener} renderer as
     *     executor rounds, never the status enricher; never null — pass a no-op ({@code event ->
     *     {}}) to reach none
     * @param childEnv the layered child-environment allowlist every vote's process environment is
     *     composed from (D6, FR9 of add-sandbox-core); never null, {@link
     *     ChildEnvAllowlist#none()} when neither passthrough nor a tracker is involved
     * @param law the invocation's frozen pipeline law, the source of acceptance-criteria content
     *     (D14 of add-sandbox-core); never null
     * @param environmentSource where each vote's environment comes from (FR15, D9 of
     *     add-sandbox-core): {@code null} keeps the host default — a {@link
     *     HostTaskExecutionEnvironment} over the {@link DirectoryWorkspace} root, today's
     *     behavior; the sandbox integration pass wires {@link FreshJudgeEnvironments} here so
     *     votes run in a fresh box materialized from the attempt commit
     */
    public CliJudgeVoter(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            ChildEnvAllowlist childEnv,
            PipelineLaw law,
            @Nullable JudgeEnvironmentSource environmentSource) {
        this.factoryProperties = factoryProperties;
        this.clock = clock;
        this.progressListener = progressListener;
        this.law = law;
        this.promptBuilder = new JudgePromptBuilder(law);
        this.environmentSource =
                environmentSource != null ? environmentSource : new HostJudgeEnvironmentSource(clock, childEnv);
    }

    /**
     * Casts one fresh CLI judge round for {@code check} (FR8, D5, D7):
     * checks the criteria file is readable first, returning immediately with
     * no process spawned if not (FR13); otherwise builds the prompt, renders
     * the hard-wired read-only invocation flags, launches the process,
     * parses its stream-json output, waits for exit within {@code
     * roundTimeout}, extracts the round's essential result, and grades the
     * final message into a {@link Verdict}.
     *
     * <p>Implements FR8, FR9, FR12, FR13, D5, D7 of add-agent-executor.
     *
     * @param check the judge check whose criteria and model settings drive the vote
     * @param context the task's identity and human decisions
     * @param workspace the working copy being graded; must be a {@link DirectoryWorkspace}
     * @return the vote's verdict and per-model token usage; never null, never throws
     */
    @Override
    public Vote vote(VerifyCheck.Judge check, TaskContext context, Workspace workspace) {
        Optional<Verdict.CannotVerify> preflight = JudgeCriteriaPreflight.checkReadable(law, check);
        if (preflight.isPresent()) {
            return new Vote(preflight.get(), Map.of());
        }

        // JudgePromptBuilder re-reads the same criteria from the frozen law
        // JudgeCriteriaPreflight just confirmed present; both read the immutable
        // in-memory snapshot, so the second read cannot fail differently — no
        // defensive try/catch is warranted (D14 of add-sandbox-core).
        String prompt = promptBuilder.build(check, context, workspace);
        return JudgeRoundExecution.run(
                factoryProperties,
                clock,
                progressListener,
                resultExtractor,
                verdictExtractor,
                check,
                environmentSource.environmentFor(workspace),
                prompt);
    }
}
