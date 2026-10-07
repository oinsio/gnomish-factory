package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import org.jspecify.annotations.Nullable;

/**
 * The manual runners: the four git-mode control flows of {@code gnomish run} — fresh and resumed,
 * on the host and in a container — and the one decision that picks among them (design D6, D11
 * and the {@code ManualRunners} row of collapse-composition-roots). A run's bindings resolve
 * fail-closed through {@link ContainerSupports#plan} (FR14, D13 of add-sandbox-core — container
 * by default, never a silent host fallback) before any git write, and the plan's mode decides
 * which runner of the pair drives it; {@link ManualRunDrive} asks for a fresh run or a resume and
 * never sees the mode.
 *
 * <p>Implements FR5-FR8 of add-git-workflow; FR14, D13 of add-sandbox-core; FR7 of
 * collapse-composition-roots; FR3 of make-run-headless.
 */
final class ManualRunners {

    private final GitModeRunner gitModeRunner;
    private final GitResumeRunner gitResumeRunner;
    private final ContainerGitModeRunner containerGitModeRunner;
    private final ContainerResumeRunner containerResumeRunner;
    private final ContainerSupports containerSupports;
    private final RegisteredClone registeredClone;

    ManualRunners(
            GitModeRunner gitModeRunner,
            GitResumeRunner gitResumeRunner,
            ContainerGitModeRunner containerGitModeRunner,
            ContainerResumeRunner containerResumeRunner,
            ContainerSupports containerSupports,
            RegisteredClone registeredClone) {
        this.gitModeRunner = gitModeRunner;
        this.gitResumeRunner = gitResumeRunner;
        this.containerGitModeRunner = containerGitModeRunner;
        this.containerResumeRunner = containerResumeRunner;
        this.containerSupports = containerSupports;
        this.registeredClone = registeredClone;
    }

    /** A fresh {@code GIT} mode run (design D8 of add-git-workflow) of the synthesized task. */
    void run(RunOrder order, TaskContext context, TaskState initialState) {
        var plan = containerSupports.plan(order.definition(), registeredClone);
        switch (plan.mode()) {
            case HOST -> gitModeRunner.run(order, context, initialState);
            case CONTAINER -> containerGitModeRunner.run(order, plan.segments(), context, initialState);
        }
    }

    /**
     * {@code --resume} (FR8 of add-git-workflow): the resolved bindings decide the resume shape (D13).
     * The operator's {@code --decision} travels here as its own argument rather than a {@link
     * RunOrder} field, since the order is also built by paths that have no decision (design D2 of
     * make-run-headless).
     *
     * @param decision the {@code --decision} text, or {@code null} when none was given
     */
    void resume(RunOrder order, String resume, @Nullable String decision) {
        var plan = containerSupports.plan(order.definition(), registeredClone);
        switch (plan.mode()) {
            case HOST -> gitResumeRunner.run(order, resume, decision);
            case CONTAINER -> containerResumeRunner.run(order, resume, decision, plan.segments());
        }
    }
}
