package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.adapter.agent.ExecutorPromptBuilder;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramFailures;
import com.github.oinsio.gnomish.adapter.agent.program.RoundExecutorFactory;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorSettings;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.Policy;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.RoleInputs;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stage half of an agent registry entry (FR14 of extract-agent-round-runner, design D12): the
 * {@link RoundExecutorFactory} the shared round wrapper ({@code ProgramStageExecutor}) runs, which puts
 * the executor bound here behind the guard probe's one owner — every agent stage round since task 5.1a,
 * which resolves it under the reserved name {@code claude}. A round's request is a {@code transform}
 * whose instructions are the round prompt {@link ExecutorPromptBuilder} composes from the frozen law —
 * the briefing with its decisions, the verify plan, the answer-file instruction and, on a retry, the
 * rework preamble, which already carries the prior attempts' findings, so the request sends no
 * feedback list of its own; its settings are the stage block's {@code model} and {@code settings}; its
 * time hints the factory's, which the runner replaces with the round's own resolution ({@link
 * RoundBounds}). No request carries a continuation (design D4, NG8).
 *
 * <p>The open round completes the request and the runner ({@link #opened}, {@link
 * #executor(RoundEnvironmentSource.Round)}): the request's policy becomes {@code writeExactly} of the
 * round's answer file — the path the round source names and reads its decision request back from (the
 * in-branch {@code EnvelopePaths.answerFile} of a box round, the host round's scratch path), which the
 * runner hands the agent in {@code GNOMISH_ANSWER_FILE} and reads its answer from, and which the
 * dialect compiles to the edit mode with a pinpoint write allowance for that one path beyond the
 * working copy, as the round's argv always was; and the runner is bound to the round's environment
 * with the round's own progress listener joined to the run's (the mid-round harvest or push). Its
 * refusals name it an agent executor.
 *
 * <p>Implements FR9, FR14 of extract-agent-round-runner.
 */
public final class AgentStageRounds implements RoundExecutorFactory {

    /** The working copy as an agent round names it: the directory the CLI starts in. */
    static final String WORKSPACE = ".";

    private final AgentRunners<?> runners;
    private final ExecutorPromptBuilder prompts;

    /**
     * @param runners the entry's runner construction; never null
     * @param law the run's frozen law, the source of each round's prompt; never null
     */
    public AgentStageRounds(AgentRunners<?> runners, PipelineLaw law) {
        this.runners = runners;
        this.prompts = new ExecutorPromptBuilder(law);
    }

    @Override
    public String name() {
        return runners.name();
    }

    @Override
    public ProgramFailures.Subject subject() {
        return ProgramFailures.Subject.AGENT;
    }

    /**
     * {@inheritDoc}
     *
     * @throws com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException when a file the prompt
     *     reads was unreadable when the law was frozen
     */
    @Override
    public ExecutorRequest request(StageExecutor.Request round) {
        var block = round.stage().executor();
        Map<String, Object> settings = new LinkedHashMap<>(block.settings());
        settings.put(ExecutorSettings.MODEL_KEY, block.model());
        return new ExecutorRequest(
                Role.TRANSFORM,
                new Workspace(WORKSPACE),
                new Policy.WorkspaceWrite(),
                RoleInputs.transform(prompts.build(round)),
                ExecutorContractJson.settingsTree(settings),
                runners.equipment().bounds().hints(),
                List.of(),
                null);
    }

    /** {@code request} under {@code writeExactly} of the open round's answer file; nothing else changes. */
    @Override
    public ExecutorRequest opened(ExecutorRequest request, RoundEnvironmentSource.Round round) {
        return new ExecutorRequest(
                request.role(),
                request.workspace(),
                new Policy.WriteExactly(round.answerFilePath().toString()),
                request.roleInputs(),
                request.settings(),
                request.bounds(),
                request.feedback(),
                request.continuation());
    }

    @Override
    public Executor executor(TaskExecutionEnvironment environment) {
        return runners.runner(environment);
    }

    /** The runner over the open round's environment, reporting to the round's own listener too. */
    @Override
    public Executor executor(RoundEnvironmentSource.Round round) {
        return runners.runner(round.environment(), round.roundListener());
    }
}
