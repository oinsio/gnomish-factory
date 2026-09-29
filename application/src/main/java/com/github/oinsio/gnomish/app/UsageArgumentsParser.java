package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * <p>Implements FR14 of add-git-workflow; FR7, FR8, UX2 of fix-operator-blockers (the directory
 * and unknown-option checks, through {@link ArgumentsParsingSupport}).
 */
final class UsageArgumentsParser {

    private static final String DIR = "dir";
    private static final String JSON = "json";
    private static final String USAGE_TOKEN = "usage";

    /** Every option {@code usage} accepts (FR8 of fix-operator-blockers). */
    private static final List<String> ACCEPTED = List.of(DIR, JSON);

    /** UX2 of fix-operator-blockers: {@code --task} is the positional task id mistyped as a flag. */
    private static final Map<String, String> POSITIONAL_HINTS = Map.of("task", "the task id is positional");

    /**
     * @param args the raw application arguments, including the leading {@code usage} token
     * @return the validated flags
     * @throws UsageException if an option is unknown, {@code --dir} is missing/malformed or the
     *     task id is absent
     */
    UsageArguments parse(ApplicationArguments args) {
        ArgumentsParsingSupport.rejectUnknownOptions(args, USAGE_TOKEN, ACCEPTED, POSITIONAL_HINTS);
        Path dir = ArgumentsParsingSupport.requiredProjectDir(args, USAGE_TOKEN);
        String task = firstPositionalAfterSubcommand(args);
        if (task == null) {
            throw new UsageException("a task id is required (e.g. gnomish usage --dir=/path/to/clone <task>)");
        }
        boolean json = args.containsOption(JSON);
        return new UsageArguments(dir, task, json);
    }

    /**
     * The task id: the first raw source argument that is neither a {@code --}-prefixed option nor
     * the leading {@code usage} subcommand token itself.
     */
    private @Nullable String firstPositionalAfterSubcommand(ApplicationArguments args) {
        return ArgumentsParsingSupport.firstPositionalAfterSubcommand(args, USAGE_TOKEN);
    }
}
