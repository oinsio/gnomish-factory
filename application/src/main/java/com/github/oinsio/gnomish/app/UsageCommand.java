package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult;
import com.github.oinsio.gnomish.app.port.git.UsageRow;
import com.github.oinsio.gnomish.domain.engine.UsageFold;
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot;
import com.github.oinsio.gnomish.usage.json.UsageReportJsonMapper;
import java.time.InstantSource;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish usage --dir <clone> <task> [--json | --markdown]} (FR14, NFR-C1 of add-git-workflow):
 * reconstructs per-stage/per-round token and time usage from the task branch's {@code state.json}
 * git history. Argument parsing is {@link UsageArgumentsParser}; the history walk is the {@link
 * com.github.oinsio.gnomish.app.port.git.TaskStoreGit} port (task 5.5); rendering is {@link UsageTextRenderer} for text, {@link
 * UsageMarkdownRenderer} for the {@code --markdown} report the usage-report stage posts (FR20 of
 * define-executor-contract), and {@link UsageReportJsonMapper} for the {@code --json} mini-contract (its own {@code "version": 1}
 * envelope, separate from status-report v1 per design D5).
 *
 * <p>"Task not found" (task 5.7, FR13, UX3, design D15): mirrors {@link StatusCommand} — a calm,
 * single-line message on the operator console followed by {@link TaskNotFoundException}, never a
 * stack trace or a WARN log; {@link RunExitCodeMapper} settles it on exit code 6.
 *
 * <p>The report is checked before it is rendered (FR18, design D15 of define-executor-contract):
 * the walked rows are re-folded through {@link UsageFold} and compared with the {@code totals} the
 * tip records, which the walk read apart from the rows. Equal, the fold is rendered; different, the
 * command renders nothing and ends with {@link UsageTotalsMismatchException}, whose message names
 * both figures — a recorded figure nothing compared would be the silent report D15 rejects.
 *
 * <p>The rendered table goes out on the console owner's human path and the {@code --json}
 * envelope on its machine path, byte for byte (FR5, UX3 of harden-untrusted-text-sinks).
 *
 * <p>Implements FR14, NFR-C1, UX3 of add-git-workflow; FR5 of harden-untrusted-text-sinks; FR3 of
 * add-project-registry; FR18, FR20 of define-executor-contract.
 */
@Component
final class UsageCommand {

    private final UsageArgumentsParser argumentsParser = new UsageArgumentsParser();
    private final TaskGit git;
    private final ProjectScope scope;
    private final UsageTextRenderer textRenderer = new UsageTextRenderer();
    private final UsageMarkdownRenderer markdownRenderer = new UsageMarkdownRenderer();
    private final UsageReportJsonMapper jsonMapper = new UsageReportJsonMapper();
    private final ConsoleIO console;
    private final InstantSource clock;

    /**
     * @param git the task-git capability set the usage history is reconstructed through; never
     *     null
     * @param scope the registered clone {@code --dir} names, whose history is read (FR3, D9 of
     *     add-project-registry); never null
     * @param console the console owner every byte this command writes goes through; never null
     * @param clock the instant the Markdown report's elapsed time runs to; never null
     */
    UsageCommand(TaskGit git, ProjectScope scope, ConsoleIO console, InstantSource clock) {
        this.git = git;
        this.scope = scope;
        this.console = console;
        this.clock = clock;
    }

    /**
     * @param args the raw application arguments, including the leading {@code usage} token
     * @throws UsageException if {@code --dir} or the task id is missing/malformed
     * @throws TaskNotFoundException if no {@code gnomish/<task>} branch exists anywhere (FR13,
     *     UX3) — printed calmly to the operator console first
     * @throws UsageTotalsMismatchException if the fold of the walked rows differs from the tip's
     *     recorded {@code totals} (FR18); nothing is printed
     */
    void run(ApplicationArguments args) {
        UsageArguments usageArguments = argumentsParser.parse(args, scope.registeredClone());
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
        List<UsageRow> rows = found.rows();
        UsageSnapshot folded =
                UsageFold.foldAll(rows.stream().map(UsageRow::attempt).toList());
        if (!folded.equals(found.recordedTotals())) {
            throw new UsageTotalsMismatchException(usageArguments.task(), folded, found.recordedTotals());
        }
        switch (usageArguments.rendering()) {
            case JSON ->
                console.printMachine(jsonMapper.serialize(usageArguments.task(), rows, folded) + ConsoleIO.LINE_END);
            case MARKDOWN -> console.print(markdownRenderer.render(rows, folded, clock.instant()) + ConsoleIO.LINE_END);
            case TEXT -> console.print(textRenderer.render(rows, folded) + ConsoleIO.LINE_END);
        }
    }
}
