package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish usage}'s command-line flags into a {@link UsageArguments} (task 5.6):
 * {@code --dir <clone>} (required), a required positional {@code <task>} id, and {@code --json}.
 * Mirrors {@link StatusArgumentsParser}'s conventions closely — same {@code --dir}/positional/
 * {@code --json} idiom — except the task id is mandatory here (FR14: {@code usage} has no list
 * mode), kept as its own small class rather than a shared parser since the two commands' required-
 * ness of the task id differs enough to make a shared abstraction not worth it at this size.
 *
 * <p>Implements FR14 of add-git-workflow.
 */
final class UsageArgumentsParser {

    private static final String DIR = "dir";
    private static final String JSON = "json";
    private static final String USAGE_TOKEN = "usage";

    /**
     * @param args the raw application arguments, including the leading {@code usage} token
     * @return the validated flags
     * @throws UsageException if {@code --dir} is missing/malformed or the task id is absent
     */
    UsageArguments parse(ApplicationArguments args) {
        Path dir = parseDir(args);
        String task = firstPositionalAfterSubcommand(args);
        if (task == null) {
            throw new UsageException("a task id is required (e.g. gnomish usage --dir=/path/to/clone <task>)");
        }
        boolean json = args.containsOption(JSON);
        return new UsageArguments(dir, task, json);
    }

    private Path parseDir(ApplicationArguments args) {
        if (!args.containsOption(DIR)) {
            throw new UsageException("--dir is required (e.g. gnomish usage --dir=/path/to/clone <task>)");
        }
        List<String> values = args.getOptionValues(DIR);
        if (values == null || values.isEmpty()) {
            throw new UsageException("--dir requires a value (e.g. --dir=/path/to/clone)");
        }
        if (values.size() > 1) {
            throw new UsageException("--dir may be given only once");
        }
        return Path.of(values.get(0));
    }

    /**
     * The task id: the first raw source argument that is neither a {@code --}-prefixed option nor
     * the leading {@code usage} subcommand token itself.
     */
    private @Nullable String firstPositionalAfterSubcommand(ApplicationArguments args) {
        boolean skippedSubcommand = false;
        for (String raw : args.getSourceArgs()) {
            if (raw.startsWith("--")) {
                continue;
            }
            if (!skippedSubcommand && raw.equals(USAGE_TOKEN)) {
                skippedSubcommand = true;
                continue;
            }
            return raw;
        }
        return null;
    }
}
