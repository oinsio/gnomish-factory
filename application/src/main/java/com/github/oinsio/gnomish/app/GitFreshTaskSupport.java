package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import org.jspecify.annotations.Nullable;

/**
 * The task-creation helper {@link GitModeRunner} needs on the fresh-run path — split out purely to
 * keep {@link GitModeRunner} within the project's file-size guidance
 * (`.claude/rules/process-invariants.md`); the behavior is unchanged from what {@link
 * GitModeRunner} used to do inline. The state/task readbacks that used to live here moved onto the
 * {@link com.github.oinsio.gnomish.app.port.git.TaskStoreGit} port (FR12b, design D12 of
 * split-into-modules): they parsed the git adapter's own file layout, which no {@code application}
 * class may know.
 *
 * <p>Implements FR6, FR7 of add-git-workflow.
 */
final class GitFreshTaskSupport {

    private GitFreshTaskSupport() {}

    /**
     * Delegates to {@link TaskRepository#createTask} — the sole branch/worktree creator on the
     * fresh-run path — remapping its {@link GitTaskRepositoryException} to a {@link
     * UsageException} (exit code 2): on a fresh run, both causes {@code createTask} can throw (an
     * already-existing branch for this taskId, or an unresolved {@code --base}) name an operator
     * mistake, not a resumable condition. {@code base} defaults to the clone's current {@code
     * HEAD} when {@code null}; {@link TaskRepository#createTask} requires a non-blank {@code
     * baseRef}, so {@code "HEAD"} is passed through literally rather than {@code null} — matching
     * {@code TaskBranchCreator}'s own "{@code null} means HEAD" convention one layer down.
     *
     * <p>{@code initialState} is the state the caller synthesized from the frozen pipeline law,
     * recorded in the STARTED commit beside the context (FR3, design D2 of
     * harden-task-branch-contract) so a first-round crash still leaves a readable branch.
     */
    static void createTask(
            TaskRepository taskRepository,
            String taskId,
            TaskContext context,
            @Nullable String base,
            TaskState initialState) {
        try {
            taskRepository.createTask(context, base == null ? "HEAD" : base, initialState);
        } catch (GitTaskRepositoryException e) {
            throw new UsageException("could not start git-mode task \"" + taskId + "\": " + e.getMessage()
                    + " — this is a fresh run, not --resume; pick a different --task-id, fix --base, or resume the"
                    + " existing task instead");
        }
    }
}
