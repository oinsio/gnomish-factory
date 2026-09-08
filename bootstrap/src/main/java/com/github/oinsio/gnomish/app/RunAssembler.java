package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.domain.engine.Engine;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.status.CompositeEngineEventListener;
import com.github.oinsio.gnomish.status.ConsoleStatusRenderer;
import com.github.oinsio.gnomish.status.LoggingEventListener;
import com.github.oinsio.gnomish.status.MdcEventListener;
import com.github.oinsio.gnomish.status.SnapshotActivityTracker;
import com.github.oinsio.gnomish.status.StatusEventListener;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@link Run} for one {@link ManualRunAssembly#assemble} call: the console, the event
 * listeners, the child-environment allowlist and the engine ports. Two responsibilities it does
 * not own live beside it — {@link RunLaw} opens and freezes the law and builds its pin guard, and
 * {@link CheckProviderWiring} derives what the configured check providers contribute — so this
 * class wires, and asks.
 *
 * <p>Implements FR7, FR10, NFR-O1, UX1, D6, D10 of add-agent-executor; D10 of add-manual-run; FR7
 * of add-git-workflow; FR11 of add-claim-heartbeat; FR16, FR26, D14 of add-sandbox-core.
 */
final class RunAssembler {

    private RunAssembler() {}

    /**
     * Builds the per-run {@link RunnerOutcomeLoop} and {@link EnginePorts} for one {@code gnomish
     * run} invocation, drawing every collaborator from {@code assembly}. {@code
     * attemptPersistence} is supplied by the caller, not fixed at construction (design D8 of
     * add-git-workflow): in-place mode passes the shared in-memory bean, git mode a fresh
     * git-backed persistence rooted at the task worktree.
     *
     * @param assembly the assembly whose fields supply every collaborator; never null
     * @param definition the loaded pipeline the run advances through; never null
     * @param context the synthesized task's identity; never null
     * @param initialState the synthesized task's initial state; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter (FR10, D6)
     * @param attemptPersistence the {@code AttemptPersistence} realization rounds commit through
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential env-var
     *     names (D17, NFR-S1 of add-tracker-port), combined here with the operator's {@code
     *     factory.sandbox.env-passthrough} into the run's {@link ChildEnvAllowlist} (D6, FR9 of
     *     add-sandbox-core) — a credential name in passthrough is refused at this seam, and the
     *     CLI executor/judge adapters and the command-check runner all compose their child
     *     environments from the resulting positive allowlist, so a credential reaches neither the
     *     gnome nor a command check by construction; empty for plain {@code gnomish run}
     * @param lawBinding which repository and tree the pipeline law is frozen from at invocation
     *     start (D14, FR19 of add-sandbox-core; D12 of add-base-ref-resolution): git objects at
     *     the law commit wherever a ref was resolved, the working tree of the {@code --dir}
     *     workspace in-place and in a manual {@code run} without {@code --base}. Never the gnome's
     *     per-task worktree, so a running task cannot rewrite its own instructions or acceptance
     *     criteria; and the read is frozen in memory, so a later edit to the same file has no
     *     effect on the running task. The binding's repository is also where the external-check
     *     pin guard reads its repository-relative pin paths — opened once by {@link RunLaw}
     * @return the outcome loop and the ports it drives; never null
     */
    static Run assemble(
            ManualRunAssembly assembly,
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            RunArguments.InteractiveMode interactiveMode,
            AttemptPersistence attemptPersistence,
            List<String> credentialEnvVarsToScrub,
            LawBinding lawBinding) {
        var runLaw = RunLaw.open(lawBinding);
        var law = runLaw.freeze(definition);
        var holder = new StatusSnapshotHolder(
                initialState, AttemptLimitResolver.resolve(definition, initialState.position()));
        var statusRenderer = new ConsoleStatusRenderer(holder, context, new StatusTextRenderer());
        var activityTracker = new SnapshotActivityTracker(holder, assembly.systemClock);
        var console = new DialogConsole(assembly.systemConsoleIO, statusRenderer, activityTracker);

        List<EngineEventListener> listeners = new ArrayList<>(List.of(
                new StatusEventListener(holder, assembly.systemClock),
                new MdcEventListener(),
                new LoggingEventListener()));
        if (assembly.extraListener != null) {
            // Task 6.1 of add-claim-heartbeat: the take run's HeartbeatProgress observes the same
            // event stream so each beat carries a live stage/attempt line. Null on every other path.
            listeners.add(assembly.extraListener);
        }
        // The run's layered child-environment allowlist (D6, FR9 of add-sandbox-core): operator
        // passthrough plus the declared credential names — the tracker's, and the external-check
        // token when that adapter is configured (FR26) — validated here, before any dialog, so a
        // credential name in passthrough fails the run at assembly time.
        var childEnv = ChildEnvAllowlist.of(
                assembly.sandboxProperties.envPassthrough(),
                CheckProviderWiring.credentialNames(assembly, definition, credentialEnvVarsToScrub));
        var listener = new CompositeEngineEventListener(listeners);
        var sandbox = assembly.sandbox;
        var builtinRunner = sandbox == null
                ? assembly.filesExistCheckRunner
                : assembly.filesExistCheckRunner.withAttemptReader(sandbox.attemptReader());
        var commandRunner = assembly.shellCommandCheckRunner.withChildEnv(childEnv);
        if (sandbox != null) {
            commandRunner = commandRunner.withEnvironments(sandbox.checkEnvironments());
        }
        var ports = new EnginePorts(
                ExecutorAdapterSelector.stageExecutor(console, interactiveMode, holder, assembly, childEnv, law),
                builtinRunner,
                commandRunner,
                CheckProviderWiring.externalCheckClient(
                        assembly,
                        console,
                        runLaw,
                        assembly.checkClientRegistry,
                        RunCheckRunContext.of(context, holder)),
                ExecutorAdapterSelector.judgeVoter(
                        console,
                        interactiveMode,
                        assembly.factoryProperties,
                        assembly.systemClock,
                        childEnv,
                        law,
                        sandbox),
                listener,
                attemptPersistence,
                assembly.systemClock,
                assembly.threadSleeper,
                sandbox == null ? AttemptDelivery.assumedDelivered() : sandbox.attemptDelivery());

        var loop = new RunnerOutcomeLoop(new Engine(), console, java.time.Clock.systemUTC());
        return new Run(loop, ports, holder);
    }
}
