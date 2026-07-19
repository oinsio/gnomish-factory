package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.BranchStateReader;
import com.github.oinsio.gnomish.adapter.git.BranchStateResult;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.TaskBranchLister;
import com.github.oinsio.gnomish.adapter.git.TaskWorktreePath;
import com.github.oinsio.gnomish.status.StatusReport;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish status --dir <clone> [<task>] [--json]} (FR13 of add-git-workflow): a read-only
 * reader over task branches — with a task id, the branch state at its tip; without one, a minimal
 * table over every {@code gnomish/*} branch (task 5.4). Argument parsing is {@link
 * StatusArgumentsParser}; branch reading is {@link BranchStateReader} (task 5.2) for the
 * single-task case and {@link TaskBranchLister} (task 5.4) for list mode; rendering reuses the
 * status-report v1 text/JSON machinery verbatim for the single-task case (FR13), mirroring {@code
 * ConsoleStatusRenderer#render}'s json-flag dispatch, and {@link TaskListRenderer} for list mode.
 * The worktree path (FR6, UX1) is printed via {@link TaskWorktreePath}'s pure formula — never
 * materialized, never touched.
 *
 * <p>"Task not found" for the single-task case (task 5.7, FR13, UX3, design D15): a merged PR's
 * branch deletion is a normal end state, not a tool failure, so the reader's {@link
 * BranchStateResult.NotFound} case prints a calm, single-line message and then signals {@link
 * TaskNotFoundException} — never a stack trace, never a WARN log — for {@link RunExitCodeMapper}
 * to settle on its own exit code (6), distinct from a clean report (0) and from the generic
 * internal-error fallback (1).
 *
 * <p>Implements FR13, FR6, UX3 of add-git-workflow.
 */
@Component
final class StatusCommand {

    private final StatusArgumentsParser argumentsParser = new StatusArgumentsParser();
    private final GitProcessRunner gitProcessRunner = new GitProcessRunner();
    private final BranchStateReader branchStateReader = new BranchStateReader(gitProcessRunner);
    private final TaskBranchLister taskBranchLister = new TaskBranchLister(gitProcessRunner);
    private final StatusTextRenderer textRenderer = new StatusTextRenderer();
    private final StatusReportJsonMapper jsonMapper = new StatusReportJsonMapper();
    private final TaskListRenderer taskListRenderer = new TaskListRenderer();
    private final Path worktreesRoot;

    StatusCommand(Path worktreesRoot) {
        this.worktreesRoot = worktreesRoot;
    }

    /**
     * @param args the raw application arguments, including the leading {@code status} token
     * @throws UsageException if {@code --dir} is missing or malformed
     * @throws TaskNotFoundException if a task id was given and no {@code gnomish/<task>} branch
     *     exists anywhere (FR13, UX3) — printed calmly to {@link System#out} first
     */
    void run(ApplicationArguments args) {
        StatusArguments statusArguments = argumentsParser.parse(args);
        String taskId = statusArguments.task();
        if (taskId == null) {
            runList(statusArguments.dir(), statusArguments.json());
            return;
        }
        runForTask(statusArguments.dir(), taskId, statusArguments.json());
    }

    private void runList(Path dir, boolean json) {
        var rows = taskBranchLister.list(dir);
        System.out.println(json ? taskListRenderer.renderJson(rows) : taskListRenderer.renderText(rows));
    }

    private void runForTask(Path dir, String taskId, boolean json) {
        BranchStateResult result = branchStateReader.read(dir, taskId);
        switch (result) {
            case BranchStateResult.NotFound ignored -> reportNotFound(taskId);
            case BranchStateResult.Found found -> printFound(dir, taskId, found.report(), json);
        }
    }

    /**
     * Prints the calm "task not found" line (UX3) and signals {@link TaskNotFoundException} —
     * branch death after a merged PR is normal, not a crash (design D15).
     */
    private void reportNotFound(String taskId) {
        System.out.println("task not found: " + taskId);
        throw new TaskNotFoundException(taskId);
    }

    private void printFound(Path dir, String taskId, StatusReport report, boolean json) {
        Path worktree = TaskWorktreePath.resolve(worktreesRoot, dir, taskId);
        System.out.println(json ? jsonMapper.serialize(report) : textRenderer.renderFull(report));
        System.out.println("Worktree: " + worktree);
    }
}
