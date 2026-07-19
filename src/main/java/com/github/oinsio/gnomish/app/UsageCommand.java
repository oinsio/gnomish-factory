package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.UsageHistoryResult;
import com.github.oinsio.gnomish.adapter.git.UsageHistoryWalker;
import com.github.oinsio.gnomish.usage.json.UsageReportJsonMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish usage --dir <clone> <task> [--json]} (FR14, NFR-C1 of add-git-workflow):
 * reconstructs per-stage/per-round token and time usage from the task branch's {@code state.json}
 * git history. Argument parsing is {@link UsageArgumentsParser}; the history walk is {@link
 * UsageHistoryWalker} (task 5.5); rendering is {@link UsageTextRenderer} for text and {@link
 * UsageReportJsonMapper} for the {@code --json} mini-contract (its own {@code "version": 1}
 * envelope, separate from status-report v1 per design D5).
 *
 * <p>"Task not found" (task 5.7, FR13, UX3, design D15): mirrors {@link StatusCommand} — a calm,
 * single-line message on {@link System#out} followed by {@link TaskNotFoundException}, never a
 * stack trace or a WARN log; {@link RunExitCodeMapper} settles it on exit code 6.
 *
 * <p>Implements FR14, NFR-C1, UX3 of add-git-workflow.
 */
@Component
final class UsageCommand {

    private final UsageArgumentsParser argumentsParser = new UsageArgumentsParser();
    private final GitProcessRunner gitProcessRunner = new GitProcessRunner();
    private final UsageHistoryWalker usageHistoryWalker = new UsageHistoryWalker(gitProcessRunner);
    private final UsageTextRenderer textRenderer = new UsageTextRenderer();
    private final UsageReportJsonMapper jsonMapper = new UsageReportJsonMapper();

    /**
     * @param args the raw application arguments, including the leading {@code usage} token
     * @throws UsageException if {@code --dir} or the task id is missing/malformed
     * @throws TaskNotFoundException if no {@code gnomish/<task>} branch exists anywhere (FR13,
     *     UX3) — printed calmly to {@link System#out} first
     */
    void run(ApplicationArguments args) {
        UsageArguments usageArguments = argumentsParser.parse(args);
        UsageHistoryResult result = usageHistoryWalker.walk(usageArguments.dir(), usageArguments.task());
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
        System.out.println("task not found: " + taskId);
        throw new TaskNotFoundException(taskId);
    }

    private void print(UsageArguments usageArguments, UsageHistoryResult.Found found) {
        if (usageArguments.json()) {
            System.out.println(jsonMapper.serialize(usageArguments.task(), found.rows(), found.totals()));
        } else {
            System.out.println(textRenderer.render(found.rows(), found.totals()));
        }
    }
}
