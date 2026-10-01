package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;

/**
 * Drives one {@code gnomish run} invocation for {@link ManualRunRunner}: parse → load {@code
 * .gnomish/} → dispatch by {@code --resume} presence, then by {@link RunArguments#mode()}. An
 * instance holding every collaborator it uses (design D6 of collapse-composition-roots): built by
 * {@link ManualRunConfiguration#manualRunDrive} and handed to {@link ManualRunRunner}, it reads no
 * field of the runner. The git-mode flows go to {@link ManualRunners}, which owns the
 * host-or-container decision; the in-place flow runs here on {@link #assembly}.
 *
 * <p>Implements FR1, FR2, FR4, FR9, FR12, NFR-O1, D9, D10 of add-manual-run; FR5-FR8, FR13, FR14,
 * UX1-UX4, design D8, D9 of add-git-workflow; FR14, D13 of add-sandbox-core; FR7 of
 * collapse-composition-roots; FR3, FR9 of add-project-registry.
 */
final class ManualRunDrive {

    private final RunArgumentsParser argumentsParser = new RunArgumentsParser();
    /** The registered clone {@code --dir} names: the directory every run works in (FR3, D9 of add-project-registry). */
    private final ProjectScope scope;

    private final PipelineStartup pipelineStartup;
    private final AdHocTaskSynthesizer taskSynthesizer;
    /**
     * The summary-carrying assembly every manual path runs on ({@link
     * ManualRunAssembly#withRunSummary}). Package-private: {@code ManualRunRunnerSpec} reads
     * {@code drive.assembly.hostGitPush} to assert the in-place assembly carries no mid-round push
     * decoration.
     */
    final ManualRunAssembly assembly;

    private final InMemoryAttemptPersistence inPlacePersistence;
    /** The console owner bound to standard output (FR5, FR6 of harden-untrusted-text-sinks). */
    private final ConsoleIO console;

    /**
     * The git-mode runners, read when a git-mode run starts: they work in the registered clone,
     * whose bean exists only once a project was resolved (D9, FR9 of add-project-registry).
     */
    private final ObjectProvider<ManualRunners> runners;

    ManualRunDrive(
            ProjectScope scope,
            PipelineStartup pipelineStartup,
            AdHocTaskSynthesizer taskSynthesizer,
            ManualRunAssembly assembly,
            InMemoryAttemptPersistence inPlacePersistence,
            ConsoleIO console,
            ObjectProvider<ManualRunners> runners) {
        this.scope = scope;
        this.pipelineStartup = pipelineStartup;
        this.taskSynthesizer = taskSynthesizer;
        this.assembly = assembly;
        this.inPlacePersistence = inPlacePersistence;
        this.console = console;
        this.runners = runners;
    }

    void drive(ApplicationArguments args) throws IOException {
        RunArguments runArguments = argumentsParser.parse(args, scope.registeredClone());
        if (runArguments.mode() == RunArguments.Mode.IN_PLACE) {
            console.print(ManualRunRunner.IN_PLACE_REMINDER + ConsoleIO.LINE_END);
        }

        PipelineLoadOutcome loadOutcome = pipelineStartup.load(runArguments);
        if (loadOutcome instanceof PipelineLoadOutcome.Failed(List<String> renderedErrors)) {
            throw new PipelineLoadFailedException(renderedErrors);
        }
        var loaded = (PipelineLoadOutcome.Loaded) loadOutcome;
        PipelineDefinition definition = loaded.definition();
        String resume = runArguments.resume();
        if (resume != null) {
            // FR8: the resolved bindings decide the resume shape (D13) — see ManualRunners.
            runners.getObject().resume(order(runArguments, definition), resume);
            return;
        }

        AdHocTaskSynthesizer.SynthesizedTask synthesized = taskSynthesizer.synthesize(runArguments, definition);
        MDC.put(ManualRunRunner.TASK_ID_KEY, synthesized.context().taskId());

        switch (runArguments.mode()) {
            case IN_PLACE -> driveInPlace(definition, synthesized, runArguments, loaded);
            case GIT ->
                runners.getObject()
                        .run(order(runArguments, definition), synthesized.context(), synthesized.initialState());
        }
    }

    /**
     * The one place a parsed {@link RunArguments} becomes a {@link RunOrder} — for the git-mode
     * runners and the in-place assembly alike (design D1 of introduce-take-order): the parsed flags plus the definition loaded from
     * {@code .gnomish/} after parsing.
     *
     * <p>Implements FR5 of introduce-take-order.
     */
    private static RunOrder order(RunArguments runArguments, PipelineDefinition definition) {
        return new RunOrder(
                runArguments.dir(),
                runArguments.base(),
                definition,
                runArguments.interactiveMode(),
                runArguments.discardWork());
    }

    /** The preserved add-manual-run flow (FR7, UX4, design D8): runs the outcome loop in-process. */
    private void driveInPlace(
            PipelineDefinition definition,
            AdHocTaskSynthesizer.SynthesizedTask synthesized,
            RunArguments runArguments,
            PipelineLoadOutcome.Loaded loaded) {
        Run run = assembly.assemble(
                order(runArguments, definition),
                synthesized.context(),
                synthesized.initialState(),
                inPlacePersistence,
                List.of(),
                LawBinding.workingTree(loaded.workspace().root()));
        run.loop().run(definition, synthesized.context(), synthesized.initialState(), loaded.workspace(), run.ports());
    }
}
