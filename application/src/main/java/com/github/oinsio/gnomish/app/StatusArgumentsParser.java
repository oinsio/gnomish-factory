package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.project.RegisteredClone;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish status}'s command-line flags into a {@link StatusArguments} (task 5.3):
 * {@code --dir <clone>} (required; resolved by the configuration loader), an optional positional {@code <task>} id, and {@code --json}.
 * Mirrors {@link RunArgumentsParser}'s conventions — Spring Boot's {@link ApplicationArguments}
 * for {@code --key=value} flags, the raw {@link ApplicationArguments#getSourceArgs()} seam for
 * the positional token (same idiom {@link Subcommand#parse} already uses), {@link UsageException}
 * for every violation.
 *
 * <p>Implements FR13, FR6 of add-git-workflow; FR7, FR8, UX2 of fix-operator-blockers (the
 * directory and unknown-option checks, through {@link ArgumentsParsingSupport}); FR3 of
 * add-project-registry (the directory is the registered clone's path, design D9).
 */
final class StatusArgumentsParser {

    private static final String DIR = "dir";
    private static final String JSON = "json";
    private static final String STATUS_TOKEN = "status";

    /** Every option {@code status} accepts (FR8 of fix-operator-blockers). */
    private static final List<String> ACCEPTED = List.of(DIR, JSON);

    /** UX2 of fix-operator-blockers: {@code --task} is the positional task id mistyped as a flag. */
    private static final Map<String, String> POSITIONAL_HINTS = Map.of("task", "the task id is positional");

    /**
     * @param args the raw application arguments, including the leading {@code status} token
     * @param clone the registered clone the configuration loader resolved from {@code --dir}; the
     *     {@code dir} component is its path (FR3, design D9 of add-project-registry)
     * @return the validated flags
     * @throws UsageException if an option is unknown
     */
    StatusArguments parse(ApplicationArguments args, RegisteredClone clone) {
        ArgumentsParsingSupport.rejectUnknownOptions(args, STATUS_TOKEN, ACCEPTED, POSITIONAL_HINTS);
        Path dir = clone.clonePath();
        String task = firstPositionalAfterSubcommand(args);
        boolean json = SwitchFlag.isOn(args, JSON);
        return new StatusArguments(dir, task, json);
    }

    /**
     * The task id: the first raw source argument that is neither a {@code --}-prefixed option
     * nor the leading {@code status} subcommand token itself.
     */
    private @Nullable String firstPositionalAfterSubcommand(ApplicationArguments args) {
        return ArgumentsParsingSupport.firstPositionalAfterSubcommand(args, STATUS_TOKEN);
    }
}
