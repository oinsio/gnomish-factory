package com.github.oinsio.gnomish.adapter.agent.command;

import com.github.oinsio.gnomish.adapter.agent.program.RoundExecutorFactory;
import com.github.oinsio.gnomish.adapter.command.CommandRun;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.CommandLine;
import com.github.oinsio.gnomish.executorcontract.Bounds;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.Policy;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.RoleInputs;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.time.InstantSource;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@link RoundExecutorFactory} of the {@code command} built-in (design D2, D6 of
 * add-command-executor): the registry name {@value #NAME}; each round's request a {@code transform}
 * whose instructions are the stage's declared command and whose settings are the stage's own — so the
 * built-in reads its {@code roundTimeout} where a program reads its settings; and each round's
 * executor a {@link CommandStageExecutor} over the round's environment on one shared {@link
 * CommandRun}. The request carries no feedback and no bounds: a command reads no request, and its one
 * bound is its {@code roundTimeout}, which the built-in enforces itself.
 *
 * <p>Implements FR4, FR8 of add-command-executor.
 */
public final class CommandExecutorFactory implements RoundExecutorFactory {

    /** The built-in's name in the executor registry, the one a {@code command} stage resolves to. */
    public static final String NAME = "command";

    /** The working copy as a command round names it: the directory {@code exec} starts in. */
    private static final String WORKSPACE = ".";

    private final CommandRun run;

    /**
     * @param clock the read-time source the round's wait measures its exit on; never null
     */
    public CommandExecutorFactory(InstantSource clock) {
        this.run = new CommandRun(CommandRun.POSIX_SHELL, clock);
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when the stage declares no command — a stage the load-time
     *     mechanism rule admits only as a {@code command} stage with one, so never in a valid run
     */
    @Override
    public ExecutorRequest request(StageExecutor.Request round) {
        var stage = round.stage();
        @Nullable CommandLine command = stage.executor().command();
        if (command == null) {
            throw new IllegalArgumentException(
                    "stage '" + stage.name() + "' declares no command, so it has no command round to run");
        }
        return new ExecutorRequest(
                Role.TRANSFORM,
                new Workspace(WORKSPACE),
                new Policy.WorkspaceWrite(),
                RoleInputs.transform(command.value()),
                ExecutorContractJson.settingsTree(stage.executor().settings()),
                Bounds.none(),
                List.of(),
                null);
    }

    @Override
    public CommandStageExecutor executor(TaskExecutionEnvironment environment) {
        return new CommandStageExecutor(environment, run);
    }
}
