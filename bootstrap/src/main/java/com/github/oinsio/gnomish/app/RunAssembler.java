package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.PinCheckedExternalCheckClient;
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory;
import com.github.oinsio.gnomish.adapter.console.InteractiveExternalCheckClient;
import com.github.oinsio.gnomish.adapter.law.PipelineLawReader;
import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.domain.engine.Engine;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.status.CompositeEngineEventListener;
import com.github.oinsio.gnomish.status.ConsoleStatusRenderer;
import com.github.oinsio.gnomish.status.LoggingEventListener;
import com.github.oinsio.gnomish.status.MdcEventListener;
import com.github.oinsio.gnomish.status.SnapshotActivityTracker;
import com.github.oinsio.gnomish.status.StatusEventListener;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the {@link Run} and selects the {@link ExternalCheckClient} for one {@link
 * ManualRunAssembly#assemble} call. Extracted from {@link ManualRunAssembly} purely to keep both
 * files within the project's file-size guidance (`.claude/rules/process-invariants.md`); the
 * behavior is unchanged.
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
     * @param lawSourceRoot the root the pipeline law is frozen from at invocation start (D14,
     *     FR19 of add-sandbox-core), against which control-file and criteria references resolve —
     *     the same root the runtime has always resolved them against: the {@code --dir} workspace
     *     root in-place, the factory clone's working-tree root in git/take modes. In git/take
     *     modes this is the clone, never the gnome's per-task worktree, so a running task cannot
     *     rewrite its own instructions or acceptance criteria; in-place it is the workspace
     *     itself, and the read is frozen in memory so a later edit to the same file has no effect
     *     on the running task
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
            Path lawSourceRoot) {
        var law = PipelineLawReader.freeze(lawSourceRoot, definition);
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
                assembly.sandboxProperties.envPassthrough(), credentialNames(assembly, credentialEnvVarsToScrub));
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
                ExecutorAdapterSelector.stageExecutor(
                        console,
                        interactiveMode,
                        holder,
                        assembly.factoryProperties,
                        assembly.systemClock,
                        childEnv,
                        law,
                        sandbox),
                builtinRunner,
                commandRunner,
                externalCheckClient(assembly, console, lawSourceRoot, assembly.githubCheckClientFactory),
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

    /**
     * The declared credential names the run's {@link ChildEnvAllowlist} refuses in passthrough
     * and scrubs from every composed child environment: the active tracker adapter's (supplied by
     * the caller), plus {@code GNOMISH_GITHUB_ACTIONS_TOKEN} whenever the GitHub Actions
     * external-check adapter is configured — the adapter declares its credential name so it can
     * never be admitted into a child environment, matching the tracker token's treatment (FR26,
     * NFR-S1 of add-sandbox-core).
     */
    private static List<String> credentialNames(ManualRunAssembly assembly, List<String> credentialEnvVarsToScrub) {
        if (!assembly.factoryProperties.check().github().configured()) {
            return credentialEnvVarsToScrub;
        }
        var names = new ArrayList<>(credentialEnvVarsToScrub);
        names.add(GithubCheckClientFactory.TOKEN_ENV_VAR);
        return names;
    }

    /**
     * Selects and pin-guards the run's {@link ExternalCheckClient} (task 8.4 of
     * add-sandbox-core): with {@code factory.check.github.*} configured, the GitHub Actions
     * adapter built by {@code checkClientFactory} (base URL and repo from config, token via the
     * {@code SecretsProvider} — a missing token fails the assembly naming the secret, FR26);
     * otherwise the interactive console client, which contributes no pin paths. Either way the
     * client is wrapped in the {@link PinCheckedExternalCheckClient} (FR16, D10) comparing against
     * the law source clone: {@code lawSourceRoot} is the factory clone checked out at the base
     * branch in git/take modes (D14), so {@code HEAD} there <em>is</em> the bound base branch; the
     * in-place mode's workspace may not be a git repository at all, in which case a check that
     * declares pin paths degrades fail-closed to CannotVerify while a pinless interactive check
     * passes vacuously. Package-private testing seam: specs call {@link
     * ManualRunAssembly#externalCheckClient} with a fake secrets provider.
     */
    static ExternalCheckClient externalCheckClient(
            ManualRunAssembly assembly,
            DialogConsole console,
            Path lawSourceRoot,
            GithubCheckClientFactory checkClientFactory) {
        var github = assembly.factoryProperties.check().github();
        ExternalCheckClient client;
        ExternalCheckPinContributor contributor;
        if (github.configured()) {
            client = checkClientFactory.create(
                    Objects.requireNonNull(github.apiUrl()), Objects.requireNonNull(github.repo()));
            contributor = checkClientFactory.pinContributor();
        } else {
            client = new InteractiveExternalCheckClient(console);
            contributor = ExternalCheckPinContributor.none();
        }
        var gitObjects = GitObjects.open(
                lawSourceRoot.resolve(".git"), Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir"))));
        return new PinCheckedExternalCheckClient(client, contributor, gitObjects, "HEAD");
    }
}
