package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult;
import com.github.oinsio.gnomish.usage.json.UsageReportJsonMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish usage --dir <clone> <task> [--json]} (FR14, NFR-C1 of add-git-workflow):
 * reconstructs per-stage/per-round token and time usage from the task branch's {@code state.json}
 * git history. Argument parsing is {@link UsageArgumentsParser}; the history walk is the {@link
 * com.github.oinsio.gnomish.app.port.git.TaskStoreGit} port (task 5.5); rendering is {@link UsageTextRenderer} for text and {@link
 * UsageReportJsonMapper} for the {@code --json} mini-contract (its own {@code "version": 1}
 * envelope, separate from status-report v1 per design D5).
 *
 * <p>"Task not found" (task 5.7, FR13, UX3, design D15): mirrors {@link StatusCommand} — a calm,
 * single-line message on the operator console followed by {@link TaskNotFoundException}, never a
 * stack trace or a WARN log; {@link RunExitCodeMapper} settles it on exit code 6.
 *
 * <p>The rendered table goes out on the console owner's human path and the {@code --json}
 * envelope on its machine path, byte for byte (FR5, UX3 of harden-untrusted-text-sinks).
 *
 * <p>Implements FR14, NFR-C1, UX3 of add-git-workflow; FR5 of harden-untrusted-text-sinks.
 */
@Component
final class UsageCommand {

    private final UsageArgumentsParser argumentsParser = new UsageArgumentsParser();
    private final TaskGit git;
    private final UsageTextRenderer textRenderer = new UsageTextRenderer();
    private final UsageReportJsonMapper jsonMapper = new UsageReportJsonMapper();
    private final ConsoleIO console;

    /**
     * @param git the task-git capability set the usage history is reconstructed through; never
     *     null
     * @param console the console owner every byte this command writes goes through; never null
     */
    UsageCommand(TaskGit git, ConsoleIO console) {
        this.git = git;
        this.console = console;
    }

    /**
     * @param args the raw application arguments, including the leading {@code usage} token
     * @throws UsageException if {@code --dir} or the task id is missing/malformed
     * @throws TaskNotFoundException if no {@code gnomish/<task>} branch exists anywhere (FR13,
     *     UX3) — printed calmly to the operator console first
     */
    void run(ApplicationArguments args) {
        UsageArguments usageArguments = argumentsParser.parse(args);
        UsageHistoryResult result = git.store().usageHistory(usageArguments.dir(), usageArguments.task());
        switch (result) {
            case UsageHistoryResult.NotFound ignored -> reportNotFound(usageArguments.task());
            case UsageHistoryResult.Found found -> print(usageArguments, found);
        }
    }

    /**
     * Prints the calm "task not found" line (UX3) and signals {@link TaskNotFoundException} —
     * branch death after a merged PR is normal, not a crash (design D15).
     */
    private void reportNotFound(String taskId) {
        console.print("task not found: " + taskId + ConsoleIO.LINE_END);
        throw new TaskNotFoundException(taskId);
    }

    private void print(UsageArguments usageArguments, UsageHistoryResult.Found found) {
        if (usageArguments.json()) {
            console.printMachine(
                    jsonMapper.serialize(usageArguments.task(), found.rows(), found.totals()) + ConsoleIO.LINE_END);
        } else {
            console.print(textRenderer.render(found.rows(), found.totals()) + ConsoleIO.LINE_END);
        }
    }
}
