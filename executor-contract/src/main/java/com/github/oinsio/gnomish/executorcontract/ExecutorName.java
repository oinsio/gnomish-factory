package com.github.oinsio.gnomish.executorcontract;

import java.util.regex.Pattern;

/**
 * The name a law gives an executor declaration, and the key the executor registry resolves a
 * stage's executor by: lower case letters, digits, {@code -} and {@code _}, starting with a letter,
 * at most 64 characters. Not a wire field of the contract.
 *
 * <p>The one owner of the name grammar: the {@code .gnomish/} loader judges a declaration's name and
 * a stage's {@code executor.name} with {@link #isName(String)} at law load, so a name the law
 * accepts is a name this type accepts, and the domain carries it as a validated string.
 *
 * <p>Implements FR1, FR9 of define-executor-contract.
 */
public record ExecutorName(String value) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    /** Refuses a value outside the name pattern. */
    public ExecutorName {
        if (!isName(value)) {
            throw new IllegalArgumentException("not an executor name (" + NAME.pattern() + "): " + value);
        }
    }

    /**
     * Whether {@code value} is an executor name: the check the law loader runs, so a law error and
     * this type's refusal never disagree (FR9 of define-executor-contract).
     *
     * @param value the candidate name; never null
     * @return true when the value matches the name grammar
     */
    public static boolean isName(String value) {
        return NAME.matcher(value).matches();
    }

    /** The name grammar as a regular expression, for a located error that states it. */
    public static String grammar() {
        return NAME.pattern();
    }

    @Override
    public String toString() {
        return value;
    }
}
