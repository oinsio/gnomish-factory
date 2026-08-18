package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.app.CheckRunContext;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The fixed, engine-defined set of values an http check may interpolate into its request, and the
 * substitution itself (NFR-S2, design D5 of add-plugin-architecture).
 *
 * <p>The whitelist is closed and small on purpose: it holds exactly what a check needs to address
 * <em>this</em> run's result — the task, its branch, the attempt commit under verification, the
 * stage asking — and nothing that could carry a secret or attacker-supplied text into a URL. Neither
 * the manifest nor operator config can widen it; a reference to anything else is a located
 * validation error at load ({@link HttpCheckParamsValidator}), so it never reaches a request.
 *
 * <p>Three values come from the run's {@link CheckRunContext}; the attempt commit comes from the
 * workspace the engine hands the client at poll time, since it changes with every round.
 *
 * <p>A reference whose value this run cannot supply fails the check closed rather than substituting
 * an empty string: a URL missing its commit addresses the wrong result, and reporting the wrong
 * result confidently is worse than reporting that the check could not run.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture.
 */
final class HttpCheckVariables {

    /** The attempt commit of the round under verification — the workspace's, not the context's. */
    static final String ATTEMPT_COMMIT = "attempt.commit";

    /** Every variable a manifest may write; nothing else is substitutable. */
    static final Set<String> WHITELIST =
            Set.of(CheckRunContext.TASK_ID, CheckRunContext.TASK_BRANCH, ATTEMPT_COMMIT, CheckRunContext.STAGE_NAME);

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]*)}");

    private final Map<String, String> values;

    private HttpCheckVariables(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * The values this poll can supply.
     *
     * @param runContext the run's whitelisted variables; never null
     * @param attemptCommit the current round's attempt commit, or null when the workspace carries
     *     none (a manual run over a plain directory)
     */
    static HttpCheckVariables of(CheckRunContext runContext, @Nullable String attemptCommit) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : WHITELIST) {
            runContext.value(name).ifPresent(value -> values.put(name, value));
        }
        if (attemptCommit != null) {
            values.put(ATTEMPT_COMMIT, attemptCommit);
        }
        return new HttpCheckVariables(values);
    }

    /**
     * Every {@code ${...}} name {@code text} references, in order of appearance — the load seam's
     * view of what a check asks for, before any run exists to answer it.
     *
     * @param text one manifest-declared url or header value; never null
     * @return the referenced names, deduplicated, in appearance order; never null
     */
    static Set<String> referencesIn(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = REFERENCE.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * {@code text} with every reference replaced by a benign token, for the load seam's syntax checks:
     * {@code ${} and {@code }} are not legal URL characters, so a url carrying a reference has to be
     * graded in the shape it will actually take rather than in the shape it was written.
     *
     * @param text one manifest-declared url or header value; never null
     * @return the text with every reference replaced; never null
     */
    static String erase(String text) {
        return REFERENCE.matcher(text).replaceAll("x");
    }

    /**
     * Substitutes every reference in {@code text}.
     *
     * @param text the manifest-declared url or header value; never null
     * @return the text with every reference replaced by this run's value
     * @throws HttpCheckVariableException if a referenced variable is outside the whitelist or this
     *     run cannot supply it — fail closed, naming the variable
     */
    String resolve(String text) {
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(valueOf(matcher.group(1))));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private String valueOf(String name) {
        String value = values.get(name);
        if (value == null) {
            throw new HttpCheckVariableException(name, WHITELIST.contains(name));
        }
        return value;
    }
}
