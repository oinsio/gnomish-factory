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
 * the briefing with its decisions, the verify plan, the answer instruction of the dialect's declared
 * answer form ({@link AnswerForm#instruction}) and, on a retry, the rework preamble, which already
 * carries the prior attempts' findings, so the request sends no feedback list of its own; its settings
 * are the stage block's {@code model} and {@code settings}; its time hints the factory's, which the
 * runner replaces with the round's own resolution ({@link RoundBounds}). No request carries a
 * continuation (design D4, NG8).
 *
 * <p>The open round completes the request and the runner ({@link #opened}, {@link
 * #executor(RoundEnvironmentSource.Round)}), as the dialect declares the answer form (FR19, design
 * D15): the runner is bound to the round's environment and its answer path — the in-branch {@code
 * EnvelopePaths.answerFile} of a box round, the host round's scratch path — with the round's own
 * progress listener joined to the run's (the mid-round harvest or push). A schema-enforced answer is
 * written there by the runner, and the request keeps its {@code workspaceWrite} policy, which grants
 * no path beyond the working copy; a dialect whose agent writes the file itself gets {@code
 * writeExactly} of that path, which it compiles to a pinpoint write allowance. Its refusals name it an
 * agent executor.
 *
 * <p>Implements FR9, FR14, FR19 of extract-agent-round-runner.
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
                RoleInputs.transform(
                        prompts.build(round, runners.answerForm(Role.TRANSFORM).instruction())),
                ExecutorContractJson.settingsTree(settings),
                runners.equipment().bounds().hints(),
                List.of(),
                null);
    }

    /**
     * {@code request} as the open round needs it, by the dialect's declared answer form for its role
     * (FR19, design D15 part 1): for a file the agent writes, under {@code writeExactly} of the open
     * round's answer file; for a schema-enforced answer unchanged, so the agent is granted no write
     * under the envelope directory — the runner writes that answer itself.
     */
    @Override
    public ExecutorRequest opened(ExecutorRequest request, RoundEnvironmentSource.Round round) {
        return switch (runners.answerForm(request.role())) {
            case AnswerForm.SchemaEnforced ignored -> request;
            case AnswerForm.AgentFile ignored ->
                new ExecutorRequest(
                        request.role(),
                        request.workspace(),
                        new Policy.WriteExactly(round.answerFilePath().toString()),
                        request.roleInputs(),
                        request.settings(),
                        request.bounds(),
                        request.feedback(),
                        request.continuation());
        };
    }

    @Override
    public Executor executor(TaskExecutionEnvironment environment) {
        return runners.runner(environment);
    }

    /**
     * The runner over the open round's environment, reporting to the round's own listener too and
     * answering at the round's answer path.
     */
    @Override
    public Executor executor(RoundEnvironmentSource.Round round) {
        return runners.runner(round);
    }
}
