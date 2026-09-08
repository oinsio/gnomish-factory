package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.Designator;
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The GitHub adapter's own designator extraction: the compiled {@code tracker.github.designators}
 * rules, and the labels-to-candidates step they drive (github-tracker spec, "Designator candidates
 * are extracted from issue labels by configured rule").
 *
 * <p>Each kind's regular expression is matched against a label name <em>in full</em> — a rule
 * {@code base:(.+)} takes {@code base:release/1.18} and leaves {@code my-base:foo} alone — and the
 * single capture group of every full match is one candidate value. Classifying those candidates
 * into absent/single/conflict is not done here: that decision belongs to {@link
 * Designator#classify}, the one function every adapter shares, so no adapter can drift on what
 * "several" means or quietly pick a winner (design D5).
 *
 * <p>Which kinds exist is configuration, not code: a kind with no rule is never extracted and never
 * reported through the factory seam, and a kind the factory has never heard of costs nothing here.
 *
 * <p>Implements FR3 of add-base-ref-resolution.
 *
 * @param byKind the compiled rule per declared designator kind
 */
record GithubDesignatorRules(Map<String, Pattern> byKind) {

    /** The subsection key the rules are declared under. */
    static final String KEY = "designators";

    /** Copies the rules, so a live adapter's extraction cannot change under it. */
    GithubDesignatorRules {
        byKind = Map.copyOf(byKind);
    }

    /**
     * Compiles the rules declared in {@code subsection}, or none when it declares no map.
     *
     * <p>The values are already graded by {@link GithubDesignatorsValidator} at load, so anything
     * this method cannot use is a configuration the loader has already refused; it skips such an
     * entry rather than throwing, because the load errors — not an adapter crash — are what the
     * operator needs to see.
     *
     * @param subsection the validated {@code tracker.github} subsection
     * @return the compiled rules; never null, empty when none are declared
     */
    static GithubDesignatorRules from(Map<String, Object> subsection) {
        if (!(subsection.get(KEY) instanceof Map<?, ?> raw)) {
            return none();
        }
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String kind
                    && !kind.isBlank()
                    && entry.getValue() instanceof String rule
                    && !rule.isBlank()) {
                compilePattern(rule).ifPresent(pattern -> compiled.put(kind, pattern));
            }
        }
        return new GithubDesignatorRules(compiled);
    }

    /** No rules at all — what a subsection with no {@code designators} map declares. */
    static GithubDesignatorRules none() {
        return new GithubDesignatorRules(Map.of());
    }

    /**
     * The kinds this adapter is configured to extract, reported through the factory seam so core can
     * validate the {@code task-branch.base.allowed} list that grades them without reading any GitHub key
     * itself.
     *
     * @return the declared kinds; never null, empty when none are declared
     */
    Set<String> kinds() {
        return byKind.keySet();
    }

    /**
     * Extracts and classifies every declared kind against one issue's label names.
     *
     * @param labelNames the names of every label on the issue, in the order the API returned them
     * @return the task's designator facts; every declared kind is present, an undeclared one reads
     *     as absent
     */
    TaskDesignators extract(List<String> labelNames) {
        Map<String, Designator> facts = new LinkedHashMap<>();
        byKind.forEach((kind, pattern) -> facts.put(kind, Designator.classify(candidates(pattern, labelNames))));
        return new TaskDesignators(facts);
    }

    /** Every full match's single capture group, in label order. */
    private static List<String> candidates(Pattern pattern, List<String> labelNames) {
        List<String> values = new ArrayList<>();
        for (String label : labelNames) {
            Matcher matcher = pattern.matcher(label);
            if (matcher.matches()) {
                values.add(matcher.group(1));
            }
        }
        return values;
    }

    /** Compiles one rule, skipping a value the loader has already refused. */
    private static Optional<Pattern> compilePattern(String rule) {
        try {
            Pattern pattern = Pattern.compile(rule);
            return pattern.matcher("").groupCount() == 1 ? Optional.of(pattern) : Optional.empty();
        } catch (PatternSyntaxException e) {
            // throwable-not-subject: a rule that does not compile is already one located ConfigError
            //     from GithubDesignatorsValidator; this adapter never runs on a config that carries
            //     one, so there is nothing here to report a second time (one failure, one log).
            return Optional.empty();
        }
    }
}
