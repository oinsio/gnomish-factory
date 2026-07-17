package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Parses and first-tier-validates {@code gnomish run}'s command-line flags into a
 * {@link RunArguments}, via Spring Boot's {@link ApplicationArguments} (design D5, task 7.1
 * scope). "First-tier" means: flags exist, are well-formed, and are mutually consistent —
 * {@code --from-stage} against the loaded pipeline's actual stage names is validated later
 * (task 7.3, design D4), and {@code --project} is never stat'd here (design D3; existence is
 * {@code DirectoryWorkspace}'s job, task 7.2/7.3).
 *
 * <p>Every violation throws {@link UsageException} with a message naming the problem and, per
 * UX1, the accepted form — including the {@code --key=value} syntax {@link ApplicationArguments}
 * requires (design D5).
 *
 * <p>Implements FR1, UX1 of add-manual-run.
 */
@Component
public final class RunArgumentsParser {

    private static final String PROJECT = "project";
    private static final String TASK = "task";
    private static final String TASK_FILE = "task-file";
    private static final String TASK_ID = "task-id";
    private static final String FROM_STAGE = "from-stage";

    /**
     * Filesystem/git-ref-safe charset for {@code --task-id}: ASCII letters, digits, {@code -}
     * and {@code _}, one or more characters. Excludes path separators ({@code /}, {@code \}),
     * whitespace, and control characters, so the value is safe to use unescaped as both a
     * filesystem path segment and a git branch/ref component.
     */
    static final Pattern TASK_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * Parses and validates {@code args} into a {@link RunArguments}.
     *
     * @param args the raw application arguments, as Spring Boot parsed them
     * @return the validated flags
     * @throws UsageException if a required flag is missing, mutually exclusive flags conflict,
     *     or a flag's value fails its format check
     */
    public RunArguments parse(ApplicationArguments args) {
        Path project = parseProject(args);
        TaskSource taskSource = parseTaskSource(args);
        String taskId = parseTaskId(args);
        String fromStage = parseFromStage(args);
        return new RunArguments(project, taskSource, taskId, fromStage);
    }

    private Path parseProject(ApplicationArguments args) {
        String value = singleValue(args, PROJECT);
        return value == null ? Path.of(".") : Path.of(value);
    }

    private TaskSource parseTaskSource(ApplicationArguments args) {
        String task = singleValue(args, TASK);
        String taskFile = singleValue(args, TASK_FILE);
        if (task != null && taskFile != null) {
            throw new UsageException("exactly one of --task or --task-file is required, not both"
                    + " (e.g. --task=\"fix the flaky spec\" or --task-file=task.md)");
        }
        if (task == null && taskFile == null) {
            throw new UsageException("exactly one of --task or --task-file is required"
                    + " (e.g. --task=\"fix the flaky spec\" or --task-file=task.md)");
        }
        return task != null ? new TaskSource.Inline(task) : new TaskSource.FromFile(Path.of(taskFile));
    }

    private @Nullable String parseTaskId(ApplicationArguments args) {
        String value = singleValue(args, TASK_ID);
        if (value == null) {
            return null;
        }
        if (!TASK_ID_PATTERN.matcher(value).matches()) {
            throw new UsageException("--task-id=" + value + " is invalid: only letters, digits, '-' and '_' are"
                    + " accepted (e.g. --task-id=my-task-1)");
        }
        return value;
    }

    private @Nullable String parseFromStage(ApplicationArguments args) {
        String value = singleValue(args, FROM_STAGE);
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new UsageException("--from-stage requires a non-blank stage name (e.g. --from-stage=build)");
        }
        return value;
    }

    /**
     * Returns the single value of {@code name}, or {@code null} if the flag is absent. Multiple
     * occurrences are rejected — {@code ApplicationArguments} would otherwise silently accept
     * {@code --task=a --task=b} and hand back the last one, hiding an operator mistake.
     */
    private @Nullable String singleValue(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return null;
        }
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            throw new UsageException("--" + name + " requires a value (e.g. --" + name + "=value)");
        }
        if (values.size() > 1) {
            throw new UsageException("--" + name + " may be given only once");
        }
        return values.get(0);
    }
}
