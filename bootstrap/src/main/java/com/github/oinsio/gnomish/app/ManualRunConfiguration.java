package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches;
import com.github.oinsio.gnomish.adapter.git.GitTaskStore;
import com.github.oinsio.gnomish.adapter.git.GitTaskWorktrees;
import com.github.oinsio.gnomish.adapter.pipeline.GnomishDirPipelineSource;
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider;
import com.github.oinsio.gnomish.app.console.SystemConsoleIO;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles every {@code gnomish run} collaborator that needs no per-invocation data — the
 * context-independent half of {@link com.github.oinsio.gnomish.domain.engine.EnginePorts}'s bean
 * graph (design D10). The remaining collaborators (the interactive adapters, the status snapshot
 * pipeline, {@code EnginePorts} itself) depend on the {@link
 * com.github.oinsio.gnomish.domain.engine.TaskContext} synthesized from {@code --task}/{@code
 * --task-file} at runtime and cannot be known at Spring context-refresh time; {@link
 * ManualRunRunner} builds those imperatively once that context exists, using the beans here as
 * building blocks.
 *
 * <p>{@link Random} and {@link Clock} beans are the two collaborators {@link
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
    public ShellCommandCheckRunner shellCommandCheckRunner(FactoryProperties factoryProperties) {
        return new ShellCommandCheckRunner().withCheckTimeout(factoryProperties.checkCommandTimeout());
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
     */
    @Bean
    public SecretsProvider secretsProvider() {
        return new EnvFileSecretsProvider();
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
     * Binds the whole task-git capability set to its git-subprocess realization (FR12b, design D12
     * of split-into-modules) — one bean, so every collaborator a run is handed necessarily comes
     * from the same backend and shares the one runner above.
     */
    @Bean
    public TaskGit taskGit(GitProcessRunner gitProcessRunner) {
        return new TaskGit(
                new GitTaskStore(gitProcessRunner),
                new GitTaskBranches(gitProcessRunner),
                new GitTaskWorktrees(gitProcessRunner));
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
     */
    @Bean
    public PipelineSource pipelineSource(
            Map<String, TrackerSubsectionValidator> trackerSubsectionValidatorRegistry,
            Map<String, CheckParamsValidator> checkParamsValidatorRegistry,
            FactoryProperties factoryProperties) {
        return new GnomishDirPipelineSource(
                trackerSubsectionValidatorRegistry,
                checkParamsValidatorRegistry,
                ConnectionProfiles.of(factoryProperties.connections()));
    }

    @Bean
    public SystemClock systemClock() {
        return new SystemClock();
    }

    @Bean
    public ThreadSleeper threadSleeper() {
        return new ThreadSleeper();
    }

    /** The real {@link com.github.oinsio.gnomish.app.port.console.ConsoleIO}, wrapping the process's own stdin/stdout. */
    @Bean
    public SystemConsoleIO systemConsoleIO() {
        return new SystemConsoleIO(System.in, System.out);
    }

    @Bean
    public Clock javaTimeClock() {
        return Clock.systemUTC();
    }

    @Bean
    public Random taskIdRandom() {
        return new Random();
    }

    @Bean
    public AdHocTaskSynthesizer adHocTaskSynthesizer(Clock javaTimeClock, Random taskIdRandom) {
        return new AdHocTaskSynthesizer(javaTimeClock, taskIdRandom);
    }

    /**
     * The root directory under which per-task git worktrees are materialized (FR6 of
     * add-git-workflow, design D6): {@code ~/.gnomish/worktrees}, outside any project clone, so
     * one factory instance can serve several projects without littering any of them.
     */
    @Bean
    public Path worktreesRoot() {
        return Path.of(System.getProperty("user.home"), ".gnomish", "worktrees");
    }

    /**
     * The user's home directory, injected rather than read inline by {@code serve}'s wiring (task
     * 5.1 of add-serve-observability) so {@link ObservabilityAssembly} — and its callers' tests —
     * can substitute a temp directory instead of touching the real {@code ~/.gnomish/serve/}
     * (FR9, design D2).
     */
    @Bean
    public Path homeDir() {
        return Path.of(System.getProperty("user.home"));
    }
}
