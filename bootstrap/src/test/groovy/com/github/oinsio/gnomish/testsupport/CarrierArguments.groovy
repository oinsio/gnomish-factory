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
 *   <li>a mint: {@code UntrustedText.subprocess(...)} and its seven siblings. The set is pinned
 *       against the leaf's own mints by
 *       {@link com.github.oinsio.gnomish.architecture.UntrustedTextSinkGateSpec}, so a family
 *       added there cannot go unseen here.
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
    static final Set<String> MINTS = [
        'UntrustedText.subprocess',
        'UntrustedText.container',
        'UntrustedText.agent',
        'UntrustedText.tracker',
        'UntrustedText.manifest',
        'UntrustedText.branchDocument',
        'UntrustedText.operator',
        'UntrustedText.factory',
    ] as Set

    /** A carrier-typed declaration: a field, a local, a parameter, a catch of one. */
    private static final Pattern DECLARATION = Pattern.compile('\\bUntrustedText\\s+(\\w+)\\s*[,;=)]')

    /**
     * The same shape for any named type: {@code GitCommandResult result =}, {@code DockerResult
     * logs)}, a field, a catch. Deliberately no generic arguments — a declaration carrying them
     * ({@code List<UntrustedText> texts}) is not a receiver an accessor is read off, and matching
     * them would cost the simple, auditable regex this scan is built on.
     */
    private static final Pattern TYPED_DECLARATION =
    Pattern.compile('\\b([A-Z]\\w*(?:\\.\\w+)*)\\s+(\\w+)\\s*[,;=)]')

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
     * Every name declared in this source, mapped to the simple name of the type it was declared
     * as — what turns {@code result.stderr()} from a bare ambiguous name into the decidable pair
     * {@code GitCommandResult.stderr} (see {@link CarrierAccessors#qualifiedNamesIn}).
     *
     * <p>A name declared twice under two types in one source maps to neither: the scan reads no
     * scopes, so it cannot tell which declaration governs the call, and guessing is how a gate
     * earns a false positive nobody can act on.
     */
    static Map<String, String> declaredTypes(String code) {
        Map<String, String> types = [:]
        def ambiguous = [] as Set
        def matcher = TYPED_DECLARATION.matcher(code)
        while (matcher.find()) {
            String name = matcher.group(2)
            String type = matcher.group(1).substring(matcher.group(1).lastIndexOf('.') + 1)
            if (types.containsKey(name) && types[name] != type) {
                ambiguous << name
            }
            types[name] = type
        }
        types.findAll { name, _ -> !ambiguous.contains(name) }
    }

    /**
     * True when this argument expression hands over a carrier.
     *
     * <p>The qualified check is purely additive to the bare-name one: a pair the receiver's
     * declared type resolves is evidence, and a pair it does not resolve falls back rather than
     * overruling, because a receiver declared as a supertype of the accessor's owner would
     * otherwise turn a detection into a silence.
     *
     * @param argument one top-level argument of a sink call, as written
     * @param accessors the accessor names whose result is a carrier, derived from bytecode
     * @param declared the carrier-typed names declared in the enclosing source
     * @param qualified the carrier accessors as owner-qualified pairs, derived from bytecode
     * @param receiverTypes the enclosing source's declarations, name to declared type
     */
    static boolean isCarrier(
            String argument,
            Set<String> accessors,
            Set<String> declared,
            Set<String> qualified = [] as Set,
            Map<String, String> receiverTypes = [:]) {
        def expression = argument.trim()
        if (expression.isEmpty() || concatenates(expression)) {
            return false
        }
        if (!expression.endsWith(')')) {
            return declared.contains(expression.replaceFirst('^this\\.', ''))
        }
        def callee = terminalCallee(expression)
        if (MINTS.contains(callee)) {
            return true
        }
        int dot = callee.lastIndexOf('.')
        def accessor = callee.substring(dot + 1)
        def owner = dot < 0 ? null : receiverTypes[callee.substring(0, dot).replaceFirst('^this\\.', '')]
        (owner != null && qualified.contains("${owner}.${accessor}".toString())) || accessors.contains(accessor)
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
