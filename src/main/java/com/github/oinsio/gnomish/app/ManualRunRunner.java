package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException;
import com.github.oinsio.gnomish.adapter.console.DialogConsole;
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO;
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The {@code gnomish run} entrypoint (design D10): runs on the {@code ApplicationRunner} thread
 * Spring Boot calls after context refresh. With none of {@code gnomish run}'s flags present, this
 * runner does nothing and lets {@link com.github.oinsio.gnomish.FactoryApplication}'s existing
 * clean-boot-and-exit-0 behavior stand untouched (FR12, proposal "no modified capabilities") —
 * that is the only branch verified by task 7.12, next. With at least one relevant flag present,
 * it drives the full pipeline: parse → load {@code .gnomish/} → synthesize the ad-hoc task →
 * assemble the per-run {@link EnginePorts} (via {@link ManualRunAssembly}) → run the {@link
 * RunnerOutcomeLoop} to a terminal outcome.
 *
 * <p>The per-run collaborators ({@link com.github.oinsio.gnomish.status.StatusSnapshotHolder},
 * {@link DialogConsole}, the interactive adapters, {@link EnginePorts} itself) all depend on the
 * {@link TaskContext} synthesized from the parsed flags — a value Spring's context-refresh time
 * knows nothing about — so {@link ManualRunAssembly} builds them imperatively, once per
 * invocation, rather than as {@code @Bean}s; {@link ManualRunConfiguration} supplies every
 * collaborator that does not need that value.
 *
 * <p>Exceptions from the pipeline below propagate uncaught past this method, except for one
 * pass-through catch/log/rethrow: {@link RunExitCodeMapper} is a registered {@code
 * ExitCodeExceptionMapper}, and Spring Boot maps an {@code ApplicationRunner}'s escaping exception
 * to an exit code through it — but Boot's own failure-reporting path logs the exception at ERROR
 * with its full stack trace first, which would defeat "no stack trace" (UX3). The catch block
 * here prints one short farewell line per exception instead (reusing each exception's own already
 * human-authored message — {@link InternalErrorException}, for instance, already carries the
 * rendered escalation text) and rethrows the identical exception unchanged, so {@link
 * RunExitCodeMapper} still receives it and maps the same exit code it would have without this
 * catch.
 *
 * <p>This runner sets the {@code taskId} MDC key once, right after {@link AdHocTaskSynthesizer}
 * resolves the task's identity — the value does not change for the rest of the run, unlike {@code
 * stage}/{@code attempt}, which the engine's own event stream drives via {@link
 * com.github.oinsio.gnomish.status.MdcEventListener} (design D9, task 8.2). The {@code finally}
 * block around the whole try/catch clears it even on the exceptional paths above, since the catch
 * blocks themselves print a farewell line after {@link #drive} returns control here — logging that
 * must not carry a stale {@code taskId} into whatever this process logs next.
 *
 * <p>Implements FR1, FR2, FR9, FR12, NFR-O1, UX3, D9, D10 of add-manual-run.
 */
@Component
public final class ManualRunRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ManualRunRunner.class);

    private static final List<String> RUN_FLAGS = List.of("project", "task", "task-file", "task-id", "from-stage");

    /** The MDC key this runner sets once {@code taskId} is known (design D9, task 8.2). */
    private static final String TASK_ID_KEY = "taskId";

    private final RunArgumentsParser argumentsParser;
    private final PipelineStartup pipelineStartup;
    private final AdHocTaskSynthesizer taskSynthesizer;
    private final ManualRunAssembly assembly;

    public ManualRunRunner(
            RunArgumentsParser argumentsParser,
            PipelineStartup pipelineStartup,
            AdHocTaskSynthesizer taskSynthesizer,
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            InMemoryAttemptPersistence attemptPersistence,
            SystemClock systemClock,
            ThreadSleeper threadSleeper) {
        this.argumentsParser = argumentsParser;
        this.pipelineStartup = pipelineStartup;
        this.taskSynthesizer = taskSynthesizer;
        this.assembly = new ManualRunAssembly(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                attemptPersistence,
                systemClock,
                threadSleeper);
    }

    /**
     * No relevant flag present → no-op, preserving {@code FactoryApplication}'s untouched no-args
     * behavior (FR12). Otherwise drives the run to completion or lets the terminal exception
     * propagate for {@link RunExitCodeMapper} to map, after printing a short farewell line (UX3).
     * The {@code finally} clears the {@code taskId} MDC key {@link #drive} sets, whether the run
     * finished normally or one of the catch blocks above just logged (NFR-O1, D9).
     *
     * <p>Implements FR1, FR2, FR9, FR12, NFR-O1, UX3, D9, D10 of add-manual-run.
     *
     * @param args the application's parsed command-line arguments; never null
     */
    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (RUN_FLAGS.stream().noneMatch(args::containsOption)) {
            return;
        }
        try {
            drive(args);
        } catch (UsageException | PipelineLoadFailedException | InternalErrorException ex) {
            System.err.println(ex.getMessage());
            throw ex;
        } catch (InputExhaustedException | ConsoleClosedException ex) {
            System.err.println("Input exhausted — stopping.");
            throw ex;
        } catch (RuntimeException | IOException ex) {
            log.warn("gnomish run terminated with an unhandled exception", ex);
            System.err.println("gnomish run failed: " + ex.getMessage());
            throw ex;
        } finally {
            MDC.remove(TASK_ID_KEY);
        }
    }

    private void drive(ApplicationArguments args) throws IOException {
        RunArguments runArguments = argumentsParser.parse(args);

        PipelineLoadOutcome loadOutcome = pipelineStartup.load(runArguments);
        if (loadOutcome instanceof PipelineLoadOutcome.Failed failed) {
            throw new PipelineLoadFailedException(failed.renderedErrors());
        }
        var loaded = (PipelineLoadOutcome.Loaded) loadOutcome;
        PipelineDefinition definition = loaded.definition();

        AdHocTaskSynthesizer.SynthesizedTask synthesized = taskSynthesizer.synthesize(runArguments, definition);
        MDC.put(TASK_ID_KEY, synthesized.context().taskId());

        ManualRunAssembly.Run run = assembly.assemble(definition, synthesized.context(), synthesized.initialState());
        run.loop().run(definition, synthesized.context(), synthesized.initialState(), loaded.workspace(), run.ports());
    }
}
