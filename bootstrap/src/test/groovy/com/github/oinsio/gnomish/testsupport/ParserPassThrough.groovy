package com.github.oinsio.gnomish.testsupport

import java.util.regex.Pattern

/**
 * Whether a class marked {@code @UntrustedParser} really parses, or just hands the captured text
 * back (FR10, design D11 of type-untrusted-text). The annotation's membership criterion is by
 * return type: a parser turns untrusted text into something that is no longer untrusted text. The
 * one shape of that criterion a scan can decide is its exact negation — a method whose returned
 * expression <em>is</em> the parsing exit:
 *
 * <pre>{@code return result.stdout().forParsing();}</pre>
 *
 * <p>That statement can only type-check as a {@code String}, so it is the escape hatch the whole
 * change exists to close, wearing the parser annotation. Everything else about the criterion — a
 * commit id, a ref name, a URL naming the syntax gate that makes it inert — is a review
 * obligation, checked by {@code /audit-implementation} on the precedent of {@code lock-scope.md},
 * because it is a claim about what a method does with a value and no scan decides that.
 *
 * <p>Shapes deliberately not flagged: {@code return Optional.of(x.forParsing())} and {@code return
 * RefNameSyntax.of(x.forParsing())} both end in a call that is not the exit, which is what
 * converting looks like.
 */
class ParserPassThrough {

    /** A return statement whose whole expression is the parsing exit — the text, handed back. */
    private static final Pattern HANDS_BACK = Pattern.compile('\\breturn\\s+[\\w.$\\[\\]()]*\\.forParsing\\(\\)\\s*;')

    /** A method declaration's opening line: a return type, then the name this scan reports. */
    private static final Pattern DECLARATION =
    Pattern.compile('^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized|default)\\s+)*' +
    '[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\(')

    /** Statements a declaration pattern would otherwise claim, all of them calls, not declarations. */
    private static final Pattern STATEMENT =
    Pattern.compile('^\\s*(?:return|throw|new|if|for|while|else|catch|switch|do|assert)\\b')

    /**
     * Every method in one already comment-stripped source that returns the captured text unchanged.
     *
     * @param code the source with comments removed
     * @param path the source's path, for the report
     * @return one {@code path:line method} entry per offending return, in source order
     */
    static List<String> handingTextBack(String code, String path) {
        def offenders = []
        def enclosing = '<unknown>'
        code.readLines().eachWithIndex { line, index ->
            def declaration = DECLARATION.matcher(line)
            if (!STATEMENT.matcher(line).find() && declaration.find()) {
                enclosing = declaration.group(1)
            }
            if (HANDS_BACK.matcher(line).find()) {
                offenders << "${path}:${index + 1} ${enclosing}".toString()
            }
        }
        offenders
    }
}
