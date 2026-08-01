package com.github.oinsio.gnomish.app;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Shared flag-parsing helpers used by the per-subcommand argument parsers ({@link
 * RunArgumentsParser}, {@link TakeArgumentsParser}, {@link StatusArgumentsParser}, {@link
 * UsageArgumentsParser}): a single-valued {@code --key=value} lookup and the "first positional
 * token after the subcommand name" idiom, both previously duplicated verbatim across those
 * classes.
 */
final class ArgumentsParsingSupport {

    private ArgumentsParsingSupport() {}

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
