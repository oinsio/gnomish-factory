package com.github.oinsio.gnomish.executorcontract;

import java.util.regex.Pattern;

/**
 * The grammar of an environment variable name an executor declares or a law grants: upper case
 * letters, digits and {@code _}, not starting with a digit. One owner for both readers — the wire
 * contract's {@code Describe} credentials and the law's {@code executors.<name>.credentials} — so a
 * name the law grants is a name describe can declare (FR9, NFR-S1 of define-executor-contract).
 *
 * <p>Implements FR1, FR9 of define-executor-contract.
 */
public final class VariableNames {

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");

    private VariableNames() {}

    /**
     * Whether {@code value} is an environment variable name.
     *
     * @param value the candidate name; never null
     * @return true when the value matches the grammar
     */
    public static boolean isVariableName(String value) {
        return VARIABLE_NAME.matcher(value).matches();
    }

    /** The grammar as a regular expression, for a located error that states it. */
    public static String grammar() {
        return VARIABLE_NAME.pattern();
    }
}
