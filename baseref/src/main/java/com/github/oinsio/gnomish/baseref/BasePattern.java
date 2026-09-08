package com.github.oinsio.gnomish.baseref;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One entry of the allowed bases, in its own grammar: a literal ref name, or a series written with {@code *}.
 *
 * <p>The grammar has exactly one metacharacter. {@code *} stands for any run of characters,
 * separators included, so {@code release/*} names the whole {@code release/1.18},
 * {@code release/1.19} series the way an operator writing it expects — and every other character is
 * literal, so {@code v1.2.3} names that tag and nothing else. Regular expressions were not chosen:
 * the allowed bases are read by whoever triages tasks, and the cost of a wrong pattern is a task
 * branched from the wrong base.
 *
 * <p>Because {@code *} crosses separators, the pattern alone cannot keep a matched value inside the
 * series an operator meant — {@code release/../../x} is text {@code release/*} would otherwise
 * accept. {@link RefNameSyntax} is the other half of the guard: a value is checked as a ref name
 * before it is matched at all.
 *
 * <p>Patterns compile at configuration load, so a malformed one is a located load error rather than
 * a claim-time surprise.
 *
 * <p>Implements FR1 of add-base-ref-resolution.
 */
public final class BasePattern {

    private final String source;
    private final Pattern compiled;

    private BasePattern(String source, Pattern compiled) {
        this.source = source;
        this.compiled = compiled;
    }

    /**
     * Why a source is not a legal allowed-base pattern — the question a configuration loader asks before it
     * compiles, so a malformed pattern becomes a located error beside every other one rather than an
     * exception it has to translate.
     *
     * @param source the pattern as the project wrote it in {@code task-branch.base.allowed}
     * @return the violated rule, phrased for a load error, or empty when {@link #compile} will
     *     succeed
     */
    public static Optional<String> violation(String source) {
        Objects.requireNonNull(source, "source");
        return RefNameSyntax.violation(source, true);
    }

    /**
     * Compiles one allowed-base pattern.
     *
     * @param source the pattern as the project wrote it in {@code task-branch.base.allowed}
     * @return the compiled pattern
     * @throws IllegalArgumentException when the source is not a well-formed ref-name pattern; the
     *     message states the violated rule, for the loader to turn into a located {@code
     *     ConfigError}
     */
    public static BasePattern compile(String source) {
        String violation = violation(source).orElse(null);
        if (violation != null) {
            throw new IllegalArgumentException("invalid allowed-base pattern '" + source + "': " + violation);
        }
        StringBuilder regex = new StringBuilder();
        for (String literal : source.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        return new BasePattern(source, Pattern.compile(regex.toString()));
    }

    /**
     * Whether a concrete ref name belongs to the series this pattern names.
     *
     * @param refName the candidate ref name; a name that is not well formed matches nothing, so the
     *     wildcard cannot be walked out of its series
     * @return true when the name is well formed and the whole of it matches
     */
    public boolean matches(String refName) {
        return RefNameSyntax.isWellFormedRefName(refName)
                && compiled.matcher(refName).matches();
    }

    /**
     * The pattern as the project wrote it — what an escalation report shows the human who has to
     * fix either the label or the allowed bases.
     *
     * @return the source text; never blank
     */
    public String source() {
        return source;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BasePattern that && source.equals(that.source);
    }

    @Override
    public int hashCode() {
        return source.hashCode();
    }

    @Override
    public String toString() {
        return source;
    }
}
