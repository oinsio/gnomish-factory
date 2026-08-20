package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;

/**
 * Drives one {@code gnomish run} invocation for {@link ManualRunRunner}: parse → load {@code
 * .gnomish/} → dispatch by {@code --resume} presence, then by {@link RunArguments#mode()}.
 * Extracted from {@link ManualRunRunner} for file size (`.claude/rules/process-invariants.md`);
 * the behavior is unchanged — every collaborator is read directly off the passed-in {@code
 * runner} instance, mirroring the {@code RunAssembly}/{@link RunAssembler} split.
 *
 * <p>Implements FR1, FR2, FR4, FR9, FR12, NFR-O1, D9, D10 of add-manual-run; FR5-FR8, FR13, FR14,
 * UX1-UX4, design D8, D9 of add-git-workflow; FR14, D13 of add-sandbox-core.
 */
final class ManualRunDrive {

    private ManualRunDrive() {}

    static void drive(ManualRunRunner runner, ApplicationArguments args) throws IOException {
        RunArguments runArguments = runner.argumentsParser.parse(args);
        if (runArguments.mode() == RunArguments.Mode.IN_PLACE) {
            System.out.println(ManualRunRunner.IN_PLACE_REMINDER);
        }

        PipelineLoadOutcome loadOutcome = runner.pipelineStartup.load(runArguments);
        if (loadOutcome instanceof PipelineLoadOutcome.Failed(List<String> renderedErrors)) {
            throw new PipelineLoadFailedException(renderedErrors);
        }
        var loaded = (PipelineLoadOutcome.Loaded) loadOutcome;
        PipelineDefinition definition = loaded.definition();
        String resume = runArguments.resume();
        if (resume != null) {
            driveResume(runner, runArguments, definition, resume);
            return;
        }

        AdHocTaskSynthesizer.SynthesizedTask synthesized = runner.taskSynthesizer.synthesize(runArguments, definition);
        MDC.put(ManualRunRunner.TASK_ID_KEY, synthesized.context().taskId());

        switch (runArguments.mode()) {
            case IN_PLACE -> driveInPlace(runner, definition, synthesized, runArguments, loaded);
            case GIT -> driveGit(runner, definition, synthesized, runArguments);
        }
    }

    /** {@code --resume} (FR8): the resolved bindings decide the resume shape (D13) — see class javadoc. */
    private static void driveResume(
            ManualRunRunner runner, RunArguments runArguments, PipelineDefinition definition, String resume) {
        var plan = SandboxModeSelector.plan(
                definition,
                runner.bindingProperties,
                runner.sandboxProperties,
                runner.bindingRegistry,
                runner.dockerProbe);
        switch (plan.mode()) {
            case HOST ->
                runner.gitResumeRunner.run(
                        runArguments.dir(),
                        resume,
                        definition,
                        runArguments.interactiveMode(),
                        runArguments.discardWork());
            case CONTAINER ->
                runner.containerResumeRunner.run(
                        runArguments.dir(),
                        resume,
                        definition,
                        plan.segments(),
                        runArguments.interactiveMode(),
                        runArguments.discardWork());
        }
    }

    /**
     * A fresh {@code GIT} mode run (design D8): bindings resolve fail-closed (FR14, D13 of
     * add-sandbox-core — container by default, never a silent host fallback) before any git write.
     */
    private static void driveGit(
            ManualRunRunner runner,
            PipelineDefinition definition,
            AdHocTaskSynthesizer.SynthesizedTask synthesized,
            RunArguments runArguments) {
        var plan = SandboxModeSelector.plan(
                definition,
                runner.bindingProperties,
                runner.sandboxProperties,
                runner.bindingRegistry,
                runner.dockerProbe);
        switch (plan.mode()) {
            case HOST ->
                runner.gitModeRunner.run(
                        runArguments.dir(),
                        runArguments.base(),
                        definition,
                        synthesized.context(),
                        synthesized.initialState(),
                        runArguments.interactiveMode());
            case CONTAINER ->
                runner.containerGitModeRunner.run(
                        runArguments.dir(),
                        runArguments.base(),
                        definition,
                        plan.segments(),
                        synthesized.context(),
                        synthesized.initialState(),
                        runArguments.interactiveMode());
        }
    }

    /** The preserved add-manual-run flow (FR7, UX4, design D8): runs the outcome loop in-process. */
    private static void driveInPlace(
            ManualRunRunner runner,
            PipelineDefinition definition,
            AdHocTaskSynthesizer.SynthesizedTask synthesized,
            RunArguments runArguments,
            PipelineLoadOutcome.Loaded loaded) {
        Run run = runner.assembly.assemble(
                definition,
                synthesized.context(),
                synthesized.initialState(),
                runArguments.interactiveMode(),
                runner.inPlacePersistence,
                List.of(),
                loaded.workspace().root());
        run.loop().run(definition, synthesized.context(), synthesized.initialState(), loaded.workspace(), run.ports());
    }
}
