package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.GitBaseRefs;
import com.github.oinsio.gnomish.adapter.git.GitInfrastructureRetry;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches;
import com.github.oinsio.gnomish.adapter.git.GitTaskStore;
import com.github.oinsio.gnomish.adapter.git.GitTaskWorktrees;
import com.github.oinsio.gnomish.adapter.git.GitVersionCheck;
import com.github.oinsio.gnomish.adapter.git.MidRoundPushRounds;
import com.github.oinsio.gnomish.adapter.pipeline.GnomishDirPipelineSource;
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider;
import com.github.oinsio.gnomish.app.console.SystemConsoleIO;
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.time.InstantSource;
import java.util.Map;
import java.util.Random;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

/**
 * Assembles every {@code gnomish run} collaborator that needs no per-invocation data — the
 * context-independent half of {@link com.github.oinsio.gnomish.domain.engine.EnginePorts}'s bean
 * graph (design D10), and the manual-run drive assembled from it (design D6 of
 * collapse-composition-roots). The remaining collaborators (the status snapshot pipeline, {@code EnginePorts} itself) depend on the {@link
 * com.github.oinsio.gnomish.domain.engine.TaskContext} synthesized from {@code --task}/{@code
 * --task-file} at runtime and cannot be known at Spring context-refresh time; {@link
 * ManualRunAssembly} builds those imperatively once that context exists, using the beans here as
 * building blocks.
 *
 * <p>{@link Random} and {@link InstantSource} beans are the two collaborators {@link
 * AdHocTaskSynthesizer} needs — kept unseeded/system-real here since a manual run always wants a
 * genuine timestamp and a genuine random suffix; tests construct their own seeded instances
 * directly rather than through this configuration (see {@code AdHocTaskSynthesizerSpec}).
 *
 * <p>Implements D10 of add-manual-run.
 */
@Configuration
public class ManualRunConfiguration {

    @Bean
    public FilesExistCheckRunner filesExistCheckRunner() {
        return new FilesExistCheckRunner();
    }

    /**
     * The {@code command}-check runner, bounded by the installation's {@code
     * factory.check-command-timeout} (FR5, FR12, design D8, D12 of bound-subprocess-commands): a
     * check that has not exited when the deadline expires is killed tree-wide and fails as a
     * quality failure carrying the tail captured so far, instead of hanging the run. Every later
     * rebind of this runner ({@code withChildEnv}, {@code withEnvironments}) carries the bound
     * along, so the value threaded here holds for every check of every mode.
     */
    @Bean
    public ShellCommandCheckRunner shellCommandCheckRunner(
            FactoryProperties factoryProperties, InstantSource instantSource) {
        return new ShellCommandCheckRunner(instantSource).withCheckTimeout(factoryProperties.checkCommandTimeout());
    }

    @Bean
    public InMemoryAttemptPersistence attemptPersistence() {
        return new InMemoryAttemptPersistence();
    }

    /**
     * The factory's single seam for named secrets (FR18, NFR-S1 of add-sandbox-core): the
     * zero-infrastructure env/file adapter, the sole implementation in this change. The tracker
     * registry injects it so {@code GNOMISH_GITHUB_TOKEN} resolves through the port, not a direct
     * environment read; Vault-class and OIDC adapters arrive later behind the same bean.
     *
     * <p>The project's and the host's secrets folders come first (FR8, design D7 of
     * add-project-registry), from the home the configuration loader resolved against; a command
     * that resolved no project consults the host folder alone.
     */
    @Bean
    public SecretsProvider secretsProvider(FactoryHome factoryHome, ObjectProvider<RegisteredClone> registeredClone) {
        RegisteredClone clone = registeredClone.getIfAvailable();
        return new EnvFileSecretsProvider(factoryHome, clone == null ? null : clone.layout());
    }

    /**
     * The one git subprocess runner every git-backed port shares (design D8 of add-git-workflow):
     * repo-level mutating commands serialize per clone through the runner, so handing every port
     * the same instance is what keeps concurrent slots correct.
     *
     * <p>Bounded by the installation's {@code factory.git-network-timeout} (FR5, design D8 of
     * bound-subprocess-commands): commands that reach a remote — {@code fetch}, {@code push},
     * {@code ls-remote}, {@code clone}, {@code remote update} — carry the deadline; local ones
     * stay unbounded.
     */
    @Bean
    public GitProcessRunner gitProcessRunner(FactoryProperties factoryProperties) {
        return new GitProcessRunner(factoryProperties.gitNetworkTimeout());
    }

    /**
     * The git version floor (FR10, design D8 of own-git-transfer-argv), checked through the same
     * runner before any command dispatches — one bean, so the once-per-process record is one
     * record.
     */
    @Bean
    public GitVersionCheck gitVersionCheck(GitProcessRunner gitProcessRunner) {
        return new GitVersionCheck(gitProcessRunner);
    }

    /**
     * The instance's record of the claim epochs its live tenures were issued (FR13 of
     * harden-task-branch-contract): one book per process, written at the take path's claim choke
     * point and read by every writer that stamps a commit with the tenure it belongs to. A bean
     * rather than a per-run object because the writers and the claim path are wired independently
     * of one another, and both must see the same book for a stamp to mean anything.
     */
    @Bean
    public ClaimEpochBook claimEpochBook() {
        return new ClaimEpochBook();
    }

    /**
     * Binds the whole task-git capability set to its git-subprocess realization (FR12b, design D12
     * of split-into-modules) — one bean, so every collaborator a run is handed necessarily comes
     * from the same backend and shares the one runner above.
     */
    @Bean
    public TaskGit taskGit(
            GitProcessRunner gitProcessRunner, ClaimEpochBook claimEpochBook, InstantSource instantSource) {
        // FR18 of supervise-daemon-loops-and-embed-dashboard: one infrastructure retry and the one
        // time source for every git collaborator below — none builds its own.
        GitInfrastructureRetry retry = GitInfrastructureRetry.system();
        return new TaskGit(
                new GitTaskStore(gitProcessRunner, claimEpochBook, retry, instantSource),
                new GitTaskBranches(gitProcessRunner, claimEpochBook, retry),
                new GitTaskWorktrees(gitProcessRunner, claimEpochBook),
                // The mid-round push decoration (FR1, design D3 of wire-host-mid-round-push),
                // built in exactly this one place: git-mode host control flows attach it via
                // RunAssembly.withHostGitPush, and the selector applies it to the host rounds.
                // The operator is stateless; per-task state (the shared poll suppressor) lives
                // in the MidRoundPushRounds instance each application creates.
                rounds -> new MidRoundPushRounds(rounds, gitProcessRunner, instantSource),
                // FR5, FR6 of add-base-ref-resolution: default-branch discovery and the narrow base
                // refresh, under the production git infrastructure retry.
                new GitBaseRefs(gitProcessRunner, retry),
                // FR4, design D2 of fix-claim-epoch-fence: the same book the three writers above
                // stamp from travels inside the bundle, so the claiming commands wrap their
                // resolved tracker with the record their own writers read.
                claimEpochBook);
    }

    /**
     * Binds the {@link com.github.oinsio.gnomish.app.port.pipeline.PipelineSource} port to the
     * {@code .gnomish/} YAML loader (FR12b, design D12 of split-into-modules), closing the
     * composition root's {@code tracker.type} → subsection-validator registry over it so a
     * malformed {@code tracker.<type>} subsection stays a located load error (FR17 of
     * add-tracker-port) without any command having to thread that registry down to the loader.
     *
     * <p>The discovered check providers' params validators are closed over the same way (FR6, FR13
     * of add-plugin-architecture), so an {@code external} check naming an undiscovered provider —
     * or a served one with malformed {@code params} — is a located load error in manual run exactly
     * as in every other mode (design D10).
     *
     * <p>The operator's named connection profiles ride along for the third cross-source reason
     * (FR16, design D8/D12): {@code factory.connections} is operator configuration while the
     * subsection referencing one is repo-side, so only this root sees both — an undefined {@code
     * connection: <name>} is therefore a located load error rather than a mid-{@code take} failure.
     *
     * <p>The providers this instance has a {@code factory.check.<provider>} section for are the
     * fourth (FR3, design D3 of remove-interactive-console): read through the same {@link
     * CheckProviderSeam#resolve} the check client is built from, so the load refuses exactly the
     * {@code external} checks no client could be built for — before any branch, worktree or
     * dialog exists, on {@code run}, {@code take} and {@code serve} alike.
     */
    @Bean
    public PipelineSource pipelineSource(
            Map<String, TrackerSubsectionValidator> trackerSubsectionValidatorRegistry,
            Map<String, CheckParamsValidator> checkParamsValidatorRegistry,
            FactoryProperties factoryProperties) {
        ConnectionProfiles profiles = ConnectionProfiles.of(factoryProperties.connections());
        return new GnomishDirPipelineSource(
                trackerSubsectionValidatorRegistry,
                checkParamsValidatorRegistry,
                CheckProviderSeam.resolve(factoryProperties.check(), profiles).keySet(),
                profiles);
    }

    /**
     * The installation's one time source (design D16, D17 of
     * supervise-daemon-loops-and-embed-dashboard): the only place real time enters production.
     * Every component that reads the current instant receives this bean, or a retry or suppressor
     * the composition root built on it.
     *
     * <p>Implements FR18 of supervise-daemon-loops-and-embed-dashboard.
     */
    @Bean
    public InstantSource instantSource() {
        return InstantSource.system();
    }

    @Bean
    public ThreadSleeper threadSleeper() {
        return new ThreadSleeper();
    }

    /**
     * The real {@link com.github.oinsio.gnomish.app.port.console.ConsoleIO}, wrapping the
     * process's own stdin/stdout — the console owner, and the default wherever a command asks for
     * one (FR5, FR6 of harden-untrusted-text-sinks).
     */
    @Bean
    @Primary
    public SystemConsoleIO systemConsoleIO() {
        return new SystemConsoleIO(System.in, System.out);
    }

    /**
     * The same owner over the process's standard error: which stream a line goes to is a wiring
     * decision, so the composition root makes it once here rather than every failure path naming
     * {@code System.err} for itself (FR6 of harden-untrusted-text-sinks). The startup-failure
     * sentences, the run's exception report and the unpersisted-abort summary write through this
     * one; everything else takes the primary.
     */
    @Bean
    public ConsoleIO errorConsoleIO() {
        return new SystemConsoleIO(System.in, System.err);
    }

    /**
     * The context-independent run assembly (design D3 of collapse-composition-roots): built once
     * here from its ingredients, so {@link ManualRunRunner} takes the assembly rather than
     * re-listing what it is made of. The runner derives its listener-carrying copy from this one
     * with {@code withExtraListener}; the plain instance is what {@code take} and {@code serve}
     * receive.
     */
    @Bean
    public ManualRunAssembly manualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            @Qualifier("errorConsoleIO") ConsoleIO errorConsoleIO,
            CheckEquipment checkEquipment,
            InstantSource instantSource,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties) {
        return new ManualRunAssembly(
                systemConsoleIO,
                errorConsoleIO,
                checkEquipment,
                instantSource,
                threadSleeper,
                factoryProperties,
                sandboxProperties);
    }

    /**
     * The installation's check equipment (design D11 and the {@code CheckEquipment} row of
     * collapse-composition-roots): the two built-in check runners, the discovered check-client
     * registry and the credential seam, which every run's check ports are built from.
     */
    @Bean
    public CheckEquipment checkEquipment(
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            Map<String, CheckClientFactory> checkClientRegistry,
            SecretsProvider secretsProvider,
            FactoryProperties factoryProperties) {
        return new CheckEquipment(
                filesExistCheckRunner,
                shellCommandCheckRunner,
                checkClientRegistry,
                secretsProvider,
                factoryProperties);
    }

    /**
     * The manual runners (design D6, D11 of collapse-composition-roots): {@code gnomish run}'s four
     * git-mode control flows over the summary-carrying assembly, with the host-or-container choice
     * they make over {@link ContainerSupports#plan}. The container pair gets {@code run}'s own
     * {@code manual} support. The host pair works in the registered clone {@code --dir} names (FR9
     * of add-project-registry); that bean exists only once the configuration loader resolved a
     * project (D9), so this bean is lazy and {@link #manualRunDrive} reads it only when a git-mode
     * run starts.
     */
    @Bean
    @Lazy
    ManualRunners manualRunners(
            ManualRunAssembly manualRunAssembly,
            TaskGit git,
            RegisteredClone registeredClone,
            ContainerSupports containerSupports,
            SystemConsoleIO systemConsoleIO) {
        ManualRunAssembly assembly = manualRunAssembly.withRunSummary();
        ContainerSupportFactory containerSupport = containerSupports.manualSupport();
        return new ManualRunners(
                new GitModeRunner(assembly, git, registeredClone, systemConsoleIO),
                new GitResumeRunner(assembly, git, registeredClone, ManualRunRunner.TASK_ID_KEY),
                new ContainerGitModeRunner(assembly, git, containerSupport, systemConsoleIO),
                new ContainerResumeRunner(assembly, git, ManualRunRunner.TASK_ID_KEY, containerSupport),
                containerSupports,
                registeredClone);
    }

    /**
     * The manual-run drive (design D6 of collapse-composition-roots): parse, load and dispatch one
     * {@code gnomish run} invocation, holding every collaborator it uses; the in-place flow runs on
     * the summary-carrying assembly, the git-mode flows on {@link #manualRunners}.
     */
    @Bean
    ManualRunDrive manualRunDrive(
            ProjectScope projectScope,
            PipelineStartup pipelineStartup,
            AdHocTaskSynthesizer adHocTaskSynthesizer,
            ManualRunAssembly manualRunAssembly,
            InMemoryAttemptPersistence attemptPersistence,
            SystemConsoleIO systemConsoleIO,
            ObjectProvider<ManualRunners> manualRunners) {
        return new ManualRunDrive(
                projectScope,
                pipelineStartup,
                adHocTaskSynthesizer,
                manualRunAssembly.withRunSummary(),
                attemptPersistence,
                systemConsoleIO,
                manualRunners);
    }

    @Bean
    public Random taskIdRandom() {
        return new Random();
    }

    @Bean
    public AdHocTaskSynthesizer adHocTaskSynthesizer(InstantSource instantSource, Random taskIdRandom) {
        return new AdHocTaskSynthesizer(instantSource, taskIdRandom);
    }
}
