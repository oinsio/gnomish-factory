package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.console.DialogConsole;
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO;
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
 * then runs {@link #driveInPlace}, {@code GIT} delegates to {@link GitModeRunner}.
 *
 * <p>Implements FR1, FR2, FR4, FR9, FR12, NFR-O1, UX3, D9, D10 of add-manual-run; FR5-FR8, FR13,
 * FR14, UX1-UX4, design D8, D9 of add-git-workflow.
 */
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

    /** The MDC key this runner sets once {@code taskId} is known (design D9, task 8.2). */
    private static final String TASK_ID_KEY = "taskId";
    /** Printed at the start of an in-place run, before the pipeline loads (FR7, UX4). */
    private static final String IN_PLACE_REMINDER =
            "in-place mode: no git, no resume — the task's progress lives only in this process;"
                    + " killing it loses all work.";

    private final RunArgumentsParser argumentsParser;
    private final PipelineStartup pipelineStartup;
    private final AdHocTaskSynthesizer taskSynthesizer;
    private final ManualRunAssembly assembly;
    private final InMemoryAttemptPersistence inPlacePersistence;
    private final GitModeRunner gitModeRunner;
    private final GitResumeRunner gitResumeRunner;
    private final SubcommandDispatch subcommandDispatch;

    ManualRunRunner(
            RunArgumentsParser argumentsParser,
            PipelineStartup pipelineStartup,
            AdHocTaskSynthesizer taskSynthesizer,
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            InMemoryAttemptPersistence attemptPersistence,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            Path worktreesRoot,
            Path homeDir,
            StatusCommand statusCommand,
            UsageCommand usageCommand,
            BoardCommand boardCommand,
            DashboardCommand dashboardCommand,
            Clock javaTimeClock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            ServeProperties serveProperties) {
        this.argumentsParser = argumentsParser;
        this.pipelineStartup = pipelineStartup;
        this.taskSynthesizer = taskSynthesizer;
        this.inPlacePersistence = attemptPersistence;
        this.assembly = new ManualRunAssembly(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                systemClock,
                threadSleeper,
                factoryProperties);
        this.gitModeRunner = new GitModeRunner(assembly, worktreesRoot);
        this.gitResumeRunner = new GitResumeRunner(assembly, worktreesRoot, TASK_ID_KEY);
        this.subcommandDispatch = SubcommandDispatchFactory.of(
                assembly,
                worktreesRoot,
                homeDir,
                TASK_ID_KEY,
                factoryProperties,
                serveProperties,
                javaTimeClock,
                systemClock,
                trackerAdapterRegistry,
                trackerValidatorRegistry,
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
                        drive(args);
                    },
                    log);
        } finally {
            MDC.remove(TASK_ID_KEY);
        }
    }

    private void drive(ApplicationArguments args) throws IOException {
        RunArguments runArguments = argumentsParser.parse(args);
        if (runArguments.mode() == RunArguments.Mode.IN_PLACE) {
            System.out.println(IN_PLACE_REMINDER);
        }

        PipelineLoadOutcome loadOutcome = pipelineStartup.load(runArguments);
        if (loadOutcome instanceof PipelineLoadOutcome.Failed(List<String> renderedErrors)) {
            throw new PipelineLoadFailedException(renderedErrors);
        }
        var loaded = (PipelineLoadOutcome.Loaded) loadOutcome;
        PipelineDefinition definition = loaded.definition();
        String resume = runArguments.resume();
        if (resume != null) {
            gitResumeRunner.run(
                    runArguments.dir(), resume, definition, runArguments.interactiveMode(), runArguments.discardWork());
            return;
        }

        AdHocTaskSynthesizer.SynthesizedTask synthesized = taskSynthesizer.synthesize(runArguments, definition);
        MDC.put(TASK_ID_KEY, synthesized.context().taskId());

        switch (runArguments.mode()) {
            case IN_PLACE -> driveInPlace(definition, synthesized, runArguments, loaded);
            case GIT ->
                gitModeRunner.run(
                        runArguments.dir(),
                        runArguments.base(),
                        definition,
                        synthesized.context(),
                        synthesized.initialState(),
                        runArguments.interactiveMode());
        }
    }

    /** The preserved add-manual-run flow (FR7, UX4, design D8): runs the outcome loop in-process. */
    private void driveInPlace(
            PipelineDefinition definition,
            AdHocTaskSynthesizer.SynthesizedTask synthesized,
            RunArguments runArguments,
            PipelineLoadOutcome.Loaded loaded) {
        Run run = assembly.assemble(
                definition,
                synthesized.context(),
                synthesized.initialState(),
                runArguments.interactiveMode(),
                inPlacePersistence,
                List.of());
        run.loop().run(definition, synthesized.context(), synthesized.initialState(), loaded.workspace(), run.ports());
    }
}
