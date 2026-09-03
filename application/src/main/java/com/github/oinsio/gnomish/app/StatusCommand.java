package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskWorktreePath;
import com.github.oinsio.gnomish.app.port.git.BranchStateResult;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.branch.RecoveryDisposition;
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
 * StatusArgumentsParser}; branch reading is {@code BranchStateReader} (task 5.2) for the
 * single-task case and {@code TaskBranchLister} (task 5.4) for list mode; rendering reuses the
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
 * <p>Every legal shape renders (FR16, UX4 of harden-task-branch-contract): a branch whose tip
 * carries no report — delivered, bare, pre-contract — prints its shape through {@link
 * BranchShapeReportRenderer}, and a quarantine shape prints its diagnosis and then signals {@link
 * BranchShapeRefusedException} for exit code 7, the same calm protocol "task not found" follows.
 *
 * <p>List mode degrades per branch, never per listing (FR13 of harden-logging-observability): one
 * unreadable branch renders as its own diagnostic row, but a ref enumeration that failed
 * established nothing about which branches exist, so {@link
 * com.github.oinsio.gnomish.app.port.git.TaskListingFailedException} propagates and the command
 * fails with the git evidence — an empty table means "verified: no tasks", never "could not look".
 *
 * <p>Implements FR13, FR6, UX3 of add-git-workflow; FR16, UX4 of harden-task-branch-contract;
 * FR13 of harden-logging-observability.
 */
@Component
final class StatusCommand {

    private final StatusArgumentsParser argumentsParser = new StatusArgumentsParser();
    private final TaskGit git;
    private final StatusTextRenderer textRenderer = new StatusTextRenderer();
    private final StatusReportJsonMapper jsonMapper = new StatusReportJsonMapper();
    private final TaskListRenderer taskListRenderer = new TaskListRenderer();
    private final BranchShapeReportRenderer shapeRenderer = new BranchShapeReportRenderer();
    private final Path worktreesRoot;

    StatusCommand(TaskGit git, Path worktreesRoot) {
        this.git = git;
        this.worktreesRoot = worktreesRoot;
    }

    /**
     * @param args the raw application arguments, including the leading {@code status} token
     * @throws UsageException if {@code --dir} is missing or malformed
     * @throws TaskNotFoundException if a task id was given and no {@code gnomish/<task>} branch
     *     exists anywhere (FR13, UX3) — printed calmly to {@link System#out} first
     * @throws BranchShapeRefusedException if the branch classifies as a quarantine shape (FR16) —
     *     its diagnosis printed calmly to {@link System#out} first
     * @throws com.github.oinsio.gnomish.app.port.git.TaskListingFailedException if list mode's ref
     *     enumeration failed; nothing is printed, since an empty table would be a false answer
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
        var rows = git.branches().list(dir);
        System.out.println(json ? taskListRenderer.renderJson(rows) : taskListRenderer.renderText(rows));
    }

    private void runForTask(Path dir, String taskId, boolean json) {
        BranchStateResult result = git.branches().readState(dir, taskId);
        switch (result) {
            case BranchStateResult.NotFound ignored -> reportNotFound(taskId);
            case BranchStateResult.Found found -> printFound(dir, taskId, found.report(), json);
            case BranchStateResult.Shaped(BranchShape shape) -> printShape(taskId, shape, json);
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

    /**
     * Renders a branch that carries no report at its tip (FR16): every legal shape prints calmly,
     * and the three quarantine shapes additionally refuse inspection with their diagnosis — nothing
     * is mutated either way.
     */
    private void printShape(String taskId, BranchShape shape, boolean json) {
        System.out.println(json ? shapeRenderer.renderJson(taskId, shape) : shapeRenderer.renderText(taskId, shape));
        if (shape.disposition() == RecoveryDisposition.QUARANTINE) {
            throw new BranchShapeRefusedException(taskId, shape);
        }
    }

    private void printFound(Path dir, String taskId, StatusReport report, boolean json) {
        Path worktree = TaskWorktreePath.resolve(worktreesRoot, dir, taskId);
        System.out.println(json ? jsonMapper.serialize(report) : textRenderer.renderFull(report));
        System.out.println("Worktree: " + worktree);
    }
}
