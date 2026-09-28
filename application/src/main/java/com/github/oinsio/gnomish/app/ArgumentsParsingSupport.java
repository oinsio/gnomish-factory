package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Shared flag-parsing helpers used by every per-subcommand argument parser: a single-valued
 * {@code --key=value} lookup, the "first positional token after the subcommand name" idiom, the
 * project directory resolved once to an absolute path, and the refusal of an option the
 * subcommand does not accept.
 *
 * <p>Implements FR7, FR8, NFR-O1, UX2 of fix-operator-blockers (the directory and unknown-option
 * helpers; design D5).
 */
final class ArgumentsParsingSupport {

    private static final String DIR = "dir";

    /** Spring Boot's own switches, accepted by every subcommand (FR8 of fix-operator-blockers). */
    private static final Set<String> SPRING_BOOT_SWITCHES = Set.of("debug", "trace");

    private ArgumentsParsingSupport() {}

    /**
     * Rejects the first option in {@code args} that {@code accepted} does not name. An option
     * whose name contains a dot is a Spring property and passes, as do Spring Boot's {@code
     * --debug} and {@code --trace}. The message names the option, the subcommand and the accepted
     * set, plus the hint {@code positionalHints} holds for that option, if any. Parsers call it
     * first, so the refusal precedes every side effect of the subcommand.
     *
     * <p>Implements FR8, NFR-R1, NFR-O1, UX2 of fix-operator-blockers.
     *
     * @param accepted the subcommand's option names without the {@code --} prefix, in the order
     *     the message lists them
     * @param positionalHints option name → the sentence telling the operator that value is
     *     positional (e.g. {@code task} → "the task id is positional")
     * @throws UsageException naming the first unknown option
     */
    static void rejectUnknownOptions(
            ApplicationArguments args,
            String subcommandToken,
            List<String> accepted,
            Map<String, String> positionalHints) {
        for (String name : args.getOptionNames().stream().sorted().toList()) {
            if (name.contains(".") || SPRING_BOOT_SWITCHES.contains(name) || accepted.contains(name)) {
                continue;
            }
            StringBuilder message = new StringBuilder("unknown option --")
                    .append(name)
                    .append(" for 'gnomish ")
                    .append(subcommandToken)
                    .append("'; accepted: ")
                    .append(String.join(
                            ", ", accepted.stream().map(a -> "--" + a).toList()));
            String hint = positionalHints.get(name);
            if (hint != null) {
                message.append("; ").append(hint);
            }
            throw new UsageException(message.toString());
        }
    }

    /**
     * Returns the single value of {@code name}, or {@code null} if the flag is absent.
     *
     * @throws UsageException if the flag is given with no value, or given more than once
     */
    static @Nullable String singleValue(ApplicationArguments args, String name) {
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
        return values.getFirst();
    }

    /**
     * The project directory of a subcommand whose {@code --dir} is optional: the {@code --dir}
     * value, or the working directory when the flag is absent, always absolute and normalized.
     * The only place a {@code --dir} value becomes a {@link Path} (design D5 of
     * fix-operator-blockers).
     *
     * <p>Implements FR7 of fix-operator-blockers.
     *
     * @throws UsageException if the flag is given with no value, or given more than once
     */
    static Path projectDir(ApplicationArguments args) {
        String value = singleValue(args, DIR);
        return resolve(value == null ? "" : value);
    }

    /**
     * The project directory of a subcommand whose {@code --dir} is required ({@code status},
     * {@code usage}): resolved exactly as {@link #projectDir}, but an absent flag is the usage
     * error those subcommands have always raised.
     *
     * <p>Implements FR7 of fix-operator-blockers.
     *
     * @throws UsageException if the flag is absent, given with no value, or given more than once
     */
    static Path requiredProjectDir(ApplicationArguments args, String subcommandToken) {
        String value = singleValue(args, DIR);
        if (value == null) {
            throw new UsageException(
                    "--dir is required (e.g. gnomish " + subcommandToken + " --dir=/path/to/clone <task>)");
        }
        return resolve(value);
    }

    /**
     * The {@code dir} component check of every {@code *Arguments} record: a directory that did
     * not come through {@link #projectDir} / {@link #requiredProjectDir} is refused at
     * construction, so no component can receive a relative project directory.
     *
     * <p>Implements FR7 of fix-operator-blockers.
     *
     * @throws IllegalArgumentException naming {@code dir} if it is not absolute
     */
    static void requireAbsoluteDir(Path dir) {
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("project directory must be absolute: " + dir);
        }
    }

    private static Path resolve(String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }

    /**
     * The first raw source argument that is neither a {@code --}-prefixed option nor the leading
     * {@code subcommandToken} itself; {@code null} if no such argument exists.
     */
    static @Nullable String firstPositionalAfterSubcommand(ApplicationArguments args, String subcommandToken) {
        boolean skippedSubcommand = false;
        for (String raw : args.getSourceArgs()) {
            if (raw.startsWith("--")) {
                continue;
            }
            if (!skippedSubcommand && raw.equals(subcommandToken)) {
                skippedSubcommand = true;
                continue;
            }
            return raw;
        }
        return null;
    }

    /**
     * Every raw source argument that is neither a {@code --}-prefixed option nor the leading
     * {@code subcommandToken} itself, in order; empty if none exist. Used by {@code take}'s batch
     * form (FR2 of add-factory-serve), which — unlike {@link #firstPositionalAfterSubcommand} —
     * needs every ref, not just the first.
     */
    static List<String> allPositionalsAfterSubcommand(ApplicationArguments args, String subcommandToken) {
        List<String> positionals = new ArrayList<>();
        boolean skippedSubcommand = false;
        for (String raw : args.getSourceArgs()) {
            if (raw.startsWith("--")) {
                continue;
            }
            if (!skippedSubcommand && raw.equals(subcommandToken)) {
                skippedSubcommand = true;
                continue;
            }
            positionals.add(raw);
        }
        return positionals;
    }
}
