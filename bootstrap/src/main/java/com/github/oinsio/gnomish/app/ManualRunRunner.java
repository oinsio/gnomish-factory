package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence;
import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.console.SystemConsoleIO;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironments;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The whole-CLI entrypoint (design D10): runs on the {@code ApplicationRunner} thread Spring Boot
 * calls after context refresh. {@link SubcommandDispatch} first tries {@code status}/{@code
 * usage}/{@code take} (FR13, FR14 of add-git-workflow; FR9 of add-tracker-port) — a bare {@code
 * gnomish take} is never confused with a bare {@code gnomish run} no-op, since the leading
 * positional token settles the subcommand before {@link #RUN_FLAGS} is ever consulted. For
 * {@code run} — explicit or implicit — with none of its flags present, this runner no-ops (FR12);
 * otherwise it drives the full pipeline: parse → load {@code .gnomish/} → dispatch by {@code
 * --resume} presence, then by {@link RunArguments#mode()}.
 *
 * <p>The per-run collaborators ({@link com.github.oinsio.gnomish.status.StatusSnapshotHolder},
 * {@link DialogConsole}, {@link EnginePorts} itself) depend on the {@link TaskContext} synthesized
 * from the parsed flags, so {@link ManualRunAssembly} builds them imperatively, once per
 * invocation, rather than as {@code @Bean}s; {@link ManualRunConfiguration} supplies every other
 * collaborator. Exception reporting (UX3) is delegated to {@link RunExceptionReporting}; the
 * {@code taskId} MDC key is cleared in {@code finally}.
 *
 * <p>{@code --resume} (FR8) delegates to {@link GitResumeRunner#run}; otherwise {@link
 * RunArguments#mode()} gates the drive (design D8): {@code IN_PLACE} prints {@link #IN_PLACE_REMINDER}
 * then runs the outcome loop in-process, {@code GIT} delegates to {@link GitModeRunner}. The drive
 * itself is delegated to {@link ManualRunDrive} for file size.
 *
 * <p>Implements FR1, FR2, FR4, FR9, FR12, NFR-O1, UX3, D9, D10 of add-manual-run; FR5-FR8, FR13,
 * FR14, UX1-UX4, design D8, D9 of add-git-workflow.
 */
// Null-marked explicitly (JSpecify): this module carries no package-info, and the application
// module's one does not reach this source root, so without the class-level marker the
// ApplicationRunner override here reads as unannotated against its null-marked supertype.
@NullMarked
@Component
public final class ManualRunRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ManualRunRunner.class);

    private static final List<String> RUN_FLAGS = List.of(
            "dir",
            "task",
            "task-file",
            "task-id",
            "from-stage",
            "interactive",
            "mode",
            "base",
            "resume",
            "discard-work");

    /**
     * The MDC key this runner sets once {@code taskId} is known (design D9, task 8.2).
     * Package-private: {@link ManualRunDrive} sets it too, once the ad-hoc task is synthesized.
     */
    static final String TASK_ID_KEY = "taskId";
    /**
     * Printed at the start of an in-place run, before the pipeline loads (FR7, UX4).
     * Package-private: printed from {@link ManualRunDrive}, extracted for file size.
     */
    static final String IN_PLACE_REMINDER =
            "in-place mode: no git, no resume — the task's progress lives only in this process;"
                    + " killing it loses all work.";

    // Package-private, not private: ManualRunDrive (extracted for file size) reads these directly
    // from the passed-in instance, mirroring ManualRunAssembly/RunAssembler's own house style.
    final RunArgumentsParser argumentsParser;
    final PipelineStartup pipelineStartup;
    final AdHocTaskSynthesizer taskSynthesizer;
    final ManualRunAssembly assembly;
    final InMemoryAttemptPersistence inPlacePersistence;
    final GitModeRunner gitModeRunner;
    final GitResumeRunner gitResumeRunner;
    final ContainerGitModeRunner containerGitModeRunner;
    final ContainerResumeRunner containerResumeRunner;
    final BindingProperties bindingProperties;
    final SandboxProperties sandboxProperties;
    private final SubcommandDispatch subcommandDispatch;

    /**
     * The container-prerequisite probe {@link SandboxModeSelector#plan} consults (D13 of
     * add-sandbox-core). Package-private test seam, mirroring {@code ManualRunAssembly}'s own
     * package-private testing seam: production keeps the real Docker probe; daemon-free specs
     * assign a scripted boolean so the container dispatch is exercised without a daemon.
     */
    BooleanSupplier dockerProbe = ContainerEnvironments::dockerAvailable;

    ManualRunRunner(
            RunArgumentsParser argumentsParser,
            PipelineStartup pipelineStartup,
            AdHocTaskSynthesizer taskSynthesizer,
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            Map<String, CheckClientFactory> checkClientRegistry,
            InMemoryAttemptPersistence attemptPersistence,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties,
            BindingProperties bindingProperties,
            TaskGit git,
            Path worktreesRoot,
            Path homeDir,
            StatusCommand statusCommand,
            UsageCommand usageCommand,
            BoardCommand boardCommand,
            DashboardCommand dashboardCommand,
            Clock javaTimeClock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            SecretsProvider secretsProvider,
            PipelineSource pipelineSource,
            ServeProperties serveProperties) {
        this.argumentsParser = argumentsParser;
        this.pipelineStartup = pipelineStartup;
        this.taskSynthesizer = taskSynthesizer;
        this.inPlacePersistence = attemptPersistence;
        this.assembly = new ManualRunAssembly(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                checkClientRegistry,
                secretsProvider,
                systemClock,
                threadSleeper,
                factoryProperties,
                sandboxProperties);
        this.gitModeRunner = new GitModeRunner(assembly, git, worktreesRoot);
        this.gitResumeRunner = new GitResumeRunner(assembly, git, worktreesRoot, TASK_ID_KEY);
        // The container support seam is bound over the check providers' own credential declarations
        // (FR17, design D11 of add-plugin-architecture): resolved once here from the discovered
        // registry, so the container environments scrub a plugin's credential with no core source
        // naming it. The seam's own signature is unchanged — the names ride in as captured state.
        // The manifest half of the same declaration (FR11): the built-in http provider names its
        // credential per check, so the loaded pipeline is asked too — through the registry, over
        // params core never interprets — and both halves reach the container's child environment.
        // A profile-resolved credential name reaches the container's scrub set exactly as an inline
        // one does (FR16, FR17, design D8/D11): the subsections are resolved against
        // `factory.connections` before the providers are asked what they name.
        List<String> checkCredentials = CheckProviderSeam.credentialEnvVars(
                CheckProviderSeam.resolve(
                        factoryProperties.check(), ConnectionProfiles.of(factoryProperties.connections())),
                checkClientRegistry);
        ContainerSupportFactory containerSupport = (clone, id, segments, sandboxProps, _, definition, creds) -> {
            var credentials = new ArrayList<>(checkCredentials);
            credentials.addAll(CheckProviderSeam.checkCredentialEnvVars(definition, checkClientRegistry));
            return ContainerRunSupport.create(clone, id, segments, sandboxProps, credentials, creds);
        };
        this.containerGitModeRunner =
                new ContainerGitModeRunner(assembly, git, sandboxProperties, factoryProperties, containerSupport);
        this.containerResumeRunner = new ContainerResumeRunner(
                assembly, git, sandboxProperties, factoryProperties, TASK_ID_KEY, containerSupport);
        this.bindingProperties = bindingProperties;
        this.sandboxProperties = sandboxProperties;
        this.subcommandDispatch = SubcommandDispatchFactory.of(
                assembly,
                git,
                worktreesRoot,
                homeDir,
                TASK_ID_KEY,
                factoryProperties,
                serveProperties,
                javaTimeClock,
                systemClock,
                trackerAdapterRegistry,
                secretsProvider,
                pipelineSource,
                statusCommand,
                usageCommand,
                boardCommand,
                dashboardCommand);
    }

    /** No relevant flag present → no-op (FR12); otherwise drives the run (see class javadoc). */
    @Override
    public void run(ApplicationArguments args) throws IOException, InterruptedException {
        try {
            RunExceptionReporting.run(
                    () -> {
                        if (subcommandDispatch.dispatchNonRun(args)
                                || RUN_FLAGS.stream().noneMatch(args::containsOption)) {
                            return;
                        }
                        ManualRunDrive.drive(this, args);
                    },
                    log);
        } finally {
            MDC.remove(TASK_ID_KEY);
        }
    }
}
