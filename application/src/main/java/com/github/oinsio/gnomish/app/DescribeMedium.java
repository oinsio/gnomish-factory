package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.domain.engine.Position;
import java.nio.file.Path;

/**
 * Where the describe preflight runs a run's declared program executors (design D5 of
 * define-executor-contract): the task's own execution environment, live, before the engine — the
 * host directory the run works in, or the task's box.
 *
 * <p>Implements FR4, FR13 of define-executor-contract.
 */
public sealed interface DescribeMedium {

    /**
     * The host: the task worktree of a git-mode run, a {@code take} run or a host resume, or the
     * {@code --dir} workspace of an in-place run.
     *
     * @param root the directory the run works in; must already exist
     */
    record Host(Path root) implements DescribeMedium {}

    /**
     * The task's box, live for the stage the engine runs first.
     *
     * @param support the run's sandbox support, bound to the task branch; never null
     * @param stage the stage the engine runs first; never blank
     */
    record Box(SandboxRunSupport support, String stage) implements DescribeMedium {}

    /**
     * No round will run: the position is past the pipeline's end, so no executor is compared and no
     * environment is opened.
     */
    record NoStageRuns() implements DescribeMedium {}

    /**
     * The task's box for the stage {@code position} runs first — the box a resume reattaches (at a
     * gate, the {@code manual} stage that passed), so the preflight and the first round share it.
     *
     * @param support the run's sandbox support; never null
     * @param position the position the engine starts from; never null
     * @return the box medium, or {@link NoStageRuns} past the pipeline's end; never null
     */
    static DescribeMedium box(SandboxRunSupport support, Position position) {
        String stage = ContainerResumePreparation.stageToReattach(position);
        return stage == null ? new NoStageRuns() : new Box(support, stage);
    }
}
