package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish status}'s command-line flags into a {@link StatusArguments} (task 5.3):
 * {@code --dir <clone>} (required), an optional positional {@code <task>} id, and {@code --json}.
 * Mirrors {@link RunArgumentsParser}'s conventions — Spring Boot's {@link ApplicationArguments}
 * for {@code --key=value} flags, the raw {@link ApplicationArguments#getSourceArgs()} seam for
 * the positional token (same idiom {@link Subcommand#parse} already uses), {@link UsageException}
 * for every violation.
 *
 * <p>Implements FR13, FR6 of add-git-workflow.
 */
final class StatusArgumentsParser {

    private static final String DIR = "dir";
    private static final String JSON = "json";
    private static final String STATUS_TOKEN = "status";

    /**
     * @param args the raw application arguments, including the leading {@code status} token
     * @return the validated flags
     * @throws UsageException if {@code --dir} is missing or given more than once
     */
    StatusArguments parse(ApplicationArguments args) {
        Path dir = parseDir(args);
        String task = firstPositionalAfterSubcommand(args);
        boolean json = args.containsOption(JSON);
        return new StatusArguments(dir, task, json);
    }

    private Path parseDir(ApplicationArguments args) {
        if (!args.containsOption(DIR)) {
            throw new UsageException("--dir is required (e.g. gnomish status --dir=/path/to/clone <task>)");
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
     * The task id: the first raw source argument that is neither a {@code --}-prefixed option
     * nor the leading {@code status} subcommand token itself.
     */
    private @Nullable String firstPositionalAfterSubcommand(ApplicationArguments args) {
        boolean skippedSubcommand = false;
        for (String raw : args.getSourceArgs()) {
            if (raw.startsWith("--")) {
                continue;
            }
            if (!skippedSubcommand && raw.equals(STATUS_TOKEN)) {
                skippedSubcommand = true;
                continue;
            }
            return raw;
        }
        return null;
    }
}
