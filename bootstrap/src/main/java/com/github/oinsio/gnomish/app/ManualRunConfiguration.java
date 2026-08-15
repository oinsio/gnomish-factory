package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory;
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

    @Bean
    public ShellCommandCheckRunner shellCommandCheckRunner() {
        return new ShellCommandCheckRunner();
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
     * The GitHub Actions external-check factory, holding the same {@link SecretsProvider} bean the
     * tracker registry uses (leak 3 of design D4, task 5.3 of split-into-modules): binding the
     * provider is the composition root's job, so the adapter no longer carries a convenience
     * constructor reaching for the env/file implementation — which was a github-adapter-to-secrets-
     * adapter edge the sibling-isolation rule counted.
     */
    @Bean
    public GithubCheckClientFactory githubCheckClientFactory(SecretsProvider secretsProvider) {
        return new GithubCheckClientFactory(secretsProvider);
    }

    /**
     * The one git subprocess runner every git-backed port shares (design D8 of add-git-workflow):
     * repo-level mutating commands serialize per clone through the runner, so handing every port
     * the same instance is what keeps concurrent slots correct.
     */
    @Bean
    public GitProcessRunner gitProcessRunner() {
        return new GitProcessRunner();
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
     */
    @Bean
    public PipelineSource pipelineSource(Map<String, TrackerSubsectionValidator> trackerSubsectionValidatorRegistry) {
        return new GnomishDirPipelineSource(trackerSubsectionValidatorRegistry);
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
