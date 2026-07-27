package com.github.oinsio.gnomish.app;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code --interactive} into its {@link RunArguments.InteractiveMode} (FR10 of
 * add-manual-run, design D6): absent &rarr; {@code NONE}; bare (no {@code =value}) &rarr; {@code
 * ALL}; {@code =executor} / {@code =judge} &rarr; the matching scoped mode. Any other value is a
 * usage error naming the accepted forms. A repeated occurrence — bare or scoped, same or different
 * value — is rejected, checked against the raw {@link ApplicationArguments#getSourceArgs()} rather
 * than {@link ApplicationArguments#getOptionValues}, because two bare occurrences both collapse to
 * an empty value list there and would otherwise be indistinguishable from one.
 *
 * <p>Extracted out of {@link RunArgumentsParser} (task 5.13, add-tracker-port) so {@link
 * TakeArgumentsParser} can reuse the identical parsing semantics without duplicating it; {@link
 * RunArgumentsParser}'s own behavior and tests are unchanged by this extraction.
 *
 * <p>Implements FR10 of add-manual-run; FR9 of add-tracker-port ({@code take}'s reuse).
 */
final class InteractiveModeParser {

    private static final String INTERACTIVE = "interactive";
    private static final String INTERACTIVE_EXECUTOR = "executor";
    private static final String INTERACTIVE_JUDGE = "judge";

    private InteractiveModeParser() {}

    static RunArguments.InteractiveMode parse(ApplicationArguments args) {
        if (!args.containsOption(INTERACTIVE)) {
            return RunArguments.InteractiveMode.NONE;
        }
        if (countOccurrences(args) > 1) {
            throw new UsageException(
                    "--interactive may be given only once (bare, --interactive=executor, or --interactive=judge)");
        }
        List<String> values = args.getOptionValues(INTERACTIVE);
        if (values == null || values.isEmpty()) {
            return RunArguments.InteractiveMode.ALL;
        }
        String value = values.getFirst();
        return switch (value) {
            case INTERACTIVE_EXECUTOR -> RunArguments.InteractiveMode.EXECUTOR_ONLY;
            case INTERACTIVE_JUDGE -> RunArguments.InteractiveMode.JUDGE_ONLY;
            default ->
                throw new UsageException("--interactive=" + value + " is invalid: accepted forms are"
                        + " --interactive, --interactive=executor, or --interactive=judge");
        };
    }

    /**
     * Counts how many raw source arguments name {@code --interactive}, either bare or with a
     * {@code =value} suffix — {@code getSourceArgs()} preserves each occurrence verbatim, unlike
     * {@code getOptionValues}, which cannot tell two bare occurrences of the same flag apart from
     * one.
     */
    private static long countOccurrences(ApplicationArguments args) {
        String prefix = "--" + INTERACTIVE;
        return Arrays.stream(args.getSourceArgs())
                .filter(raw -> raw.equals(prefix) || raw.startsWith(prefix + "="))
                .count();
    }
}
