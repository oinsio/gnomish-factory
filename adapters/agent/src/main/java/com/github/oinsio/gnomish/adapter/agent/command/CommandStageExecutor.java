package com.github.oinsio.gnomish.adapter.agent.command;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.oinsio.gnomish.adapter.agent.runner.RoundBounds;
import com.github.oinsio.gnomish.adapter.command.CommandRun;
import com.github.oinsio.gnomish.app.describe.ExecutorRoles;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The {@code command} built-in of the executor contract (FR4, FR5, FR8 of add-command-executor; design
 * D1, D2, D5): a {@code transform} run is the request's instructions — the stage's declared command —
 * run as {@code sh -c} through the round's {@link TaskExecutionEnvironment}, the sole process seam, on
 * the shared {@link CommandRun} core, bounded by the overall bound {@link RoundBounds} resolves for
 * the request — its declared {@code roundTimeout}, or the factory's overall bound the executor is
 * constructed with (FR7, D6 of extract-agent-round-runner). It reports the contract's {@link ExecutorResult} only, by the
 * exit-code contract {@link CommandRoundReport} owns: the mapping to the engine's result is {@code
 * ProgramResultMapping}'s, in the shared round wrapper.
 *
 * <p>The child environment is the environment's layered allowlist alone: the built-in adds no variable
 * of its own, so no {@code GNOMISH_ANSWER_FILE} reaches the command and no answer is ever returned —
 * a command escalates by failing (D5). The run reports a known zero of model use and its wall time
 * (NFR-C1, D8), so its describe declares that it reports usage. {@link #poll} answers
 * {@code failed/infrastructure}: a command round is never {@code pending}.
 *
 * <p>Its roles are {@link BuiltInExecutors#COMMAND_ROLES}, the one statement the judge role rule also
 * reads, mapped through {@link ExecutorRoles}; {@link #start} refuses a role its describe does not
 * list, never by a spelling of its own (FR13, D12 of extract-agent-round-runner).
 *
 * <p>Implements FR3, FR4, FR5, FR6, FR7, FR8, NFR-C1, NFR-S1, NG1 of add-command-executor; FR7, FR13 of
 * extract-agent-round-runner.
 */
public final class CommandStageExecutor implements Executor {

    private final TaskExecutionEnvironment environment;
    private final CommandRun run;
    private final ProgramBounds factoryBounds;

    /**
     * @param environment the open round's execution environment; never null
     * @param run the shared command-run core; never null
     * @param factoryBounds the factory's bounds, whose overall bound applies when a stage declares no
     *     {@code roundTimeout}; never null
     */
    public CommandStageExecutor(TaskExecutionEnvironment environment, CommandRun run, ProgramBounds factoryBounds) {
        this.environment = environment;
        this.run = run;
        this.factoryBounds = factoryBounds;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The settings schema states only that the settings are an object: which keys a command stage
     * takes and the shapes of {@code roundTimeout} are the load-time validation's
     * ({@code CommandSettingsValidator}) and {@code RoundTimeout}'s, never a second statement here.
     */
    @Override
    public Describe describe() {
        return describeAnswer();
    }

    /**
     * The describe answer, the same for every binding: what the executor registry entry carries
     * before any check or round is bound (design D18 of migrate-verify-to-executor-contract).
     *
     * @return a fresh answer; never null
     */
    public static Describe describeAnswer() {
        return new Describe(
                Describe.SPOKEN_PROTOCOL_VERSION,
                UntrustedText.factory(BuiltInExecutors.VERSION),
                BuiltInExecutors.COMMAND_ROLES.stream()
                        .map(ExecutorRoles::contract)
                        .toList(),
                List.of(),
                JsonNodeFactory.instance.objectNode().put("type", "object"),
                List.of(),
                List.of(),
                null,
                List.of(),
                EnforcementLevel.PROMPT_ONLY,
                true,
                false);
    }

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        String command = request.roleInputs().instructions();
        if (!describeAnswer().roles().contains(request.role()) || command == null) {
            return CommandRoundReport.refused("the command built-in runs transform rounds only, and was asked for a "
                    + request.role().wire() + " run");
        }
        Duration timeout = RoundBounds.overall(factoryBounds, request);
        return CommandRoundReport.of(command, timeout, run.run(environment, command, Map.of(), timeout));
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        return CommandRoundReport.refused("a command round is never pending, so there is nothing to poll");
    }
}
