package com.github.oinsio.gnomish.testsupport

import java.util.regex.Pattern

/**
 * Whether one argument expression, as written in the source, hands a sink an untrusted-text
 * carrier (rule (c), design D2 of type-untrusted-text). ArchUnit cannot answer this — it sees call
 * targets and parameter types, never the static type of an argument expression — so the question
 * is decided on the source, in the shape the retired accessor gate used.
 *
 * <p>Three ways an argument's static type is the carrier, and no fourth that a sink call can
 * write:
 *
 * <ul>
 *   <li>a carrier-typed name — a local, a field or a parameter declared {@code UntrustedText x}
 *       in the same source, passed as itself;
 *   <li>a call whose result is a carrier: the terminal call of the expression is one of the
 *       accessor names derived from bytecode ({@link CarrierAccessors});
 *   <li>a mint: {@code UntrustedText.subprocess(...)} and its six siblings.
 * </ul>
 *
 * <p>Two shapes are deliberately not violations. An <b>exit</b> ({@code x.stderr().forLog()}) is
 * the whole point of the rule: its terminal call returns a {@code String}. A <b>concatenation</b>
 * ({@code "push failed: " + result.stderr()}) yields a {@code String} too, and design D1 makes
 * that string the log exit's output rather than a hole — which is why the carrier renders itself
 * that way instead of throwing.
 */
class CarrierArguments {

    /** The mints, by their dotted form: the whole expression is a freshly captured carrier. */
    private static final Set<String> MINTS = [
        'UntrustedText.subprocess',
        'UntrustedText.container',
        'UntrustedText.agent',
        'UntrustedText.tracker',
        'UntrustedText.manifest',
        'UntrustedText.branchDocument',
        'UntrustedText.operator',
    ] as Set

    /** A carrier-typed declaration: a field, a local, a parameter, a catch of one. */
    private static final Pattern DECLARATION = Pattern.compile('\\bUntrustedText\\s+(\\w+)\\s*[,;=)]')

    /** Every name declared as a carrier in this already comment-stripped source. */
    static Set<String> declaredNames(String code) {
        def names = [] as Set
        def matcher = DECLARATION.matcher(code)
        while (matcher.find()) {
            names << matcher.group(1)
        }
        names
    }

    /**
     * True when this argument expression hands over a carrier.
     *
     * @param argument one top-level argument of a sink call, as written
     * @param accessors the accessor names whose result is a carrier, derived from bytecode
     * @param declared the carrier-typed names declared in the enclosing source
     */
    static boolean isCarrier(String argument, Set<String> accessors, Set<String> declared) {
        def expression = argument.trim()
        if (expression.isEmpty() || concatenates(expression)) {
            return false
        }
        if (!expression.endsWith(')')) {
            return declared.contains(expression.replaceFirst('^this\\.', ''))
        }
        def callee = terminalCallee(expression)
        MINTS.contains(callee) || accessors.contains(callee.substring(callee.lastIndexOf('.') + 1))
    }

    /**
     * The dotted name of the call the expression ends with — what decides its static type.
     * {@code result.stderr()} ends in {@code stderr}, {@code result.stderr().forLog()} in
     * {@code forLog}, and {@code LogText.forLog(result.stderr())} in {@code LogText.forLog}: the
     * nested accessor is the wrapper's argument, not this expression's result.
     */
    private static String terminalCallee(String expression) {
        int depth = 0
        for (int i = expression.length() - 1; i >= 0; i--) {
            char c = expression.charAt(i)
            if (c == ')' as char) {
                depth++
            } else if (c == '(' as char && --depth == 0) {
                return nameEndingAt(expression, i)
            }
        }
        ''
    }

    /** The dotted name immediately preceding the open paren at {@code index}. */
    private static String nameEndingAt(String expression, int index) {
        int start = index
        while (start> 0 && (Character.isLetterOrDigit(expression.charAt(start - 1) as char)
                || expression.charAt(start - 1) in ['.' as char, '_' as char])) {
            start--
        }
        expression.substring(start, index)
    }

    /** True when a {@code +} joins this expression at its own level, making the result a string. */
    private static boolean concatenates(String expression) {
        int depth = 0
        for (int i = 0; i <expression.length(); i++) {
            char c = expression.charAt(i)
            if (c == '"' as char || c == '\'' as char) {
                i = JavaCallSites.endOfLiteral(expression, i)
                if (i < 0) {
                    return false
                }
            } else if (c in ['(' as char, '[' as char]) {
                depth++
            } else if (c in [')' as char, ']' as char]) {
                depth--
            } else if (c == '+' as char && depth == 0) {
                return true
            }
        }
        false
    }
}
