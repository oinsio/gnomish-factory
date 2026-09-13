package com.github.oinsio.gnomish.adapter.tracker.github;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * The one definition of what a {@code tracker.github.designators} rule is: a regular expression
 * that compiles and carries exactly one capture group — the designator value the adapter takes.
 *
 * <p>Single owner of that acceptance criterion, for both sides that need it: {@link
 * GithubDesignatorsValidator} turns a graded rule's problem into one located {@link
 * com.github.oinsio.gnomish.domain.pipeline.ConfigError} at load, and {@link GithubDesignatorRules}
 * takes the pattern of a rule that has none. Before this class the two graded separately and had to
 * be kept in step by hand — a validator that accepted a rule the extractor then skipped would have
 * produced a kind declared in configuration, reported clean at load, and silently never extracted.
 * The two cannot diverge now: a rule has a pattern if and only if it has no problem, by
 * construction.
 *
 * <p>Implements FR3 of add-base-ref-resolution.
 */
final class DesignatorRule {

    private static final String NOT_A_REGEX = "'%s' is not a valid regular expression: %s";

    private static final String ONE_GROUP = "'%s' must contain exactly one capture group — the designator value — "
            + "but has %d; make the extra groups non-capturing with '(?:...)'";

    private final @Nullable Pattern pattern;

    private final @Nullable String problem;

    private DesignatorRule(@Nullable Pattern pattern, @Nullable String problem) {
        this.pattern = pattern;
        this.problem = problem;
    }

    /**
     * Grades one declared rule.
     *
     * @param rule the raw regular expression as declared in configuration
     * @return the graded rule: a usable pattern, or the operator-facing reason it is not one
     */
    static DesignatorRule grade(String rule) {
        Pattern compiled;
        try {
            compiled = Pattern.compile(rule);
        } catch (PatternSyntaxException e) {
            // throwable-not-subject: the description is the operator's located ConfigError message;
            //     an unusable rule is a configuration the loader refuses, not a failure to log.
            return new DesignatorRule(null, NOT_A_REGEX.formatted(rule, e.getDescription()));
        }
        int groups = compiled.matcher("").groupCount();
        return groups == 1
                ? new DesignatorRule(compiled, null)
                : new DesignatorRule(null, ONE_GROUP.formatted(rule, groups));
    }

    /**
     * The compiled pattern, present exactly when {@link #problem()} is empty.
     *
     * @return the pattern to extract candidates with; empty for a rule the loader refuses
     */
    Optional<Pattern> pattern() {
        return Optional.ofNullable(pattern);
    }

    /**
     * The reason this rule is unusable, present exactly when {@link #pattern()} is empty.
     *
     * @return the located error's message; empty for a usable rule
     */
    Optional<String> problem() {
        return Optional.ofNullable(problem);
    }
}
