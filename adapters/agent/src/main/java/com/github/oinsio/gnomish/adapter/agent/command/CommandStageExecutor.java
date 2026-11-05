package com.github.oinsio.gnomish.adapter.agent.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.oinsio.gnomish.adapter.agent.RoundTimeout;
import com.github.oinsio.gnomish.adapter.command.CommandRun;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.PendingKey;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code command} built-in of the executor contract (FR4, FR5, FR8 of add-command-executor; design
 * D1, D2, D5): a {@code transform} run is the request's instructions — the stage's declared command —
 * run as {@code sh -c} through the round's {@link TaskExecutionEnvironment}, the sole process seam, on
 * the shared {@link CommandRun} core, bounded by the request's {@code roundTimeout} as the shared
 * {@link RoundTimeout} resolves it. It reports the contract's {@link ExecutorResult} only, by the
 * exit-code contract {@link CommandRoundReport} owns: the mapping to the engine's result is {@code
 * ProgramResultMapping}'s, in the shared round wrapper.
 *
 * <p>The child environment is the environment's layered allowlist alone: the built-in adds no variable
 * of its own, so no {@code GNOMISH_DECISION_FILE} reaches the command and no answer is ever returned —
 * a command escalates by failing (D5). The run reports a known zero of model use and its wall time
 * (NFR-C1, D8), so its describe declares that it reports usage. {@link #poll} answers
 * {@code failed/infrastructure}: a command round is never {@code pending}.
 *
 * <p>Implements FR3, FR4, FR5, FR6, FR7, FR8, NFR-C1, NFR-S1, NG1 of add-command-executor.
 */
public final class CommandStageExecutor implements Executor {

    private static final TypeReference<Map<String, Object>> SETTINGS = new TypeReference<>() {};

    private final TaskExecutionEnvironment environment;
    private final CommandRun run;

    /**
     * @param environment the open round's execution environment; never null
     * @param run the shared command-run core; never null
     */
    public CommandStageExecutor(TaskExecutionEnvironment environment, CommandRun run) {
        this.environment = environment;
        this.run = run;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The settings schema states only that the settings are an object: which keys a command stage
     * takes and the shapes of {@code roundTimeout} are the load-time validation's
     * ({@code CommandSettingsValidator}) and {@link RoundTimeout}'s, never a second statement here.
     */
    @Override
    public Describe describe() {
        return new Describe(
                Describe.SPOKEN_PROTOCOL_VERSION,
                UntrustedText.factory("built-in"),
                List.of(Role.TRANSFORM),
                List.of(),
                JsonNodeFactory.instance.objectNode().put("type", "object"),
                List.of(),
                List.of(),
                null,
                List.of(),
                EnforcementLevel.PROMPT_ONLY,
                true);
    }

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        String command = request.roleInputs().instructions();
        if (request.role() != Role.TRANSFORM || command == null) {
            return CommandRoundReport.refused("the command built-in runs transform rounds only, and was asked for a "
                    + request.role().wire() + " run");
        }
        Duration timeout = RoundTimeout.resolve(settings(request.settings()));
        return CommandRoundReport.of(command, timeout, run.run(environment, command, Map.of(), timeout));
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        return CommandRoundReport.refused("a command round is never pending, so there is nothing to poll");
    }

    /** The request's settings as the plain JDK values the law loader mapped them from. */
    private static Map<String, Object> settings(JsonNode settings) {
        try {
            return Objects.requireNonNull(
                    ExecutorContractJson.reader().forType(SETTINGS).readValue(settings));
        } catch (IOException e) {
            throw new UncheckedIOException("a request's settings tree could not be read back as values", e);
        }
    }
}
