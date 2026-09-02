package com.github.oinsio.gnomish.testsupport

import groovy.transform.Canonical

import java.util.function.Predicate
import java.util.regex.Pattern

/**
 * Extraction of SLF4J call sites from production sources, shared by the two convention gates of
 * harden-logging-observability (D9): ThrowableConventionGateSpec (FR7) and
 * UntrustedLogTextGateSpec (FR6). Both need the same thing — one log call's full argument list,
 * however many lines it spans — so the parser lives here once.
 *
 * <p>The scan runs over comment-stripped code ({@link RepoSourceTree#code}), because a convention
 * gate must judge what the compiler sees; the raw lines are kept alongside so an in-place
 * exemption comment can still be found (see {@link #exempted}).
 */
class LogCallSites {

    /** A mis-built scanner would make both gates pass over an empty call set. */
    static final int KNOWN_LOG_CALLS = 200

    /**
     * Both SLF4J call shapes. The classic one carries its arguments in the level method's own
     * parens; the fluent one ({@code log.atLevel(x).log(msg, a, b)}, {@code
     * log.atInfo().setMessage(...).log()}) carries them further down a builder chain, so a match
     * on {@code atInfo(} alone would hand the gates an argument list that is always empty — a
     * silent blind spot rather than a visible one. {@link #inSource} therefore extends a fluent
     * match through the chain to its terminal {@code .log(...)}, and both gates see one call text
     * either way.
     */
    private static final Pattern LOG_CALL = Pattern.compile(
    '\\b(?:log|logger|LOG|LOGGER)\\.(?:error|warn|info|debug|trace|at(?:Level|Error|Warn|Info|Debug|Trace))\\s*\\(')

    /** One call site: where it is, and the whole call text with comments removed. */
    @Canonical
    static class LogCall {
        String path
        int line
        String text
    }

    /** Every log call in every production source, optionally narrowed by relative path. */
    static List<LogCall> productionCalls(Predicate<String> filter = {
                true
            }) {
        RepoSourceTree.productionSources(filter).collectMany { file ->
            inSource(RepoSourceTree.code(file), RepoSourceTree.relative(file))
        }
    }

    /** Every log call in one already comment-stripped source text. */
    static List<LogCall> inSource(String code, String path) {
        scan(code, path).calls
    }

    /**
     * The log calls in every production source whose argument list the parser could not delimit —
     * an unbalanced literal left by comment stripping, a fluent chain whose {@code .log(...)} is
     * not in this source. Every gate asserts this is empty: a site the parser drops is a site
     * neither gate judges, and a scanner that fails open reports the same green as a codebase with
     * no violations in it.
     */
    static List<String> unparsedProductionCalls(Predicate<String> filter = {
                true
            }) {
        RepoSourceTree.productionSources(filter).collectMany { file ->
            scan(RepoSourceTree.code(file), RepoSourceTree.relative(file)).unparsed
        }
    }

    /** One source's scan: the calls whose text was delimited, and the sites where that failed. */
    @Canonical
    static class Scan {
        List<LogCall> calls
        List<String> unparsed
    }

    /** Both halves of one already comment-stripped source's scan. */
    static Scan scan(String code, String path) {
        def calls = []
        def unparsed = []
        def matcher = LOG_CALL.matcher(code)
        while (matcher.find()) {
            int line = code.take(matcher.start()).count('\n') + 1
            int close = closingParen(code, matcher.end() - 1)
            if (close < 0) {
                unparsed << "${path}:${line}".toString()
                continue
            }
            int end = code.substring(matcher.start(), matcher.end()).contains('.at')
                    ? endOfFluentChain(code, close)
                    : close
            if (end < 0) {
                unparsed << "${path}:${line}".toString()
                continue
            }
            calls << new LogCall(path, line, code.substring(matcher.start(), end + 1))
        }
        new Scan(calls, unparsed)
    }

    /**
     * True when the call carries an in-place exemption marker — on one of its own raw lines, or in
     * the comment block directly above it. The same idiom {@code .claude/rules/logging.md}
     * documents, so a justification lives beside the call rather than in a central allowlist.
     */
    static boolean exempted(File file, LogCall call, String marker) {
        def lines = file.readLines()
        int first = call.line - 1
        int last = Math.min(lines.size(), first + call.text.count('\n') + 1)
        def window = new ArrayList<String>(lines[first..<last])
        while (first> 0 && isCommentOrBlank(lines[first - 1])) {
            window << lines[--first]
        }
        window.any { it.contains(marker) }
    }

    private static boolean isCommentOrBlank(String line) {
        def trimmed = line.trim()
        trimmed.isEmpty() || trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')
    }

    /**
     * Index of the paren closing the fluent chain's terminal {@code .log(...)}, starting from the
     * paren that closed {@code atLevel(...)}. Consumes {@code .name(...)} segments in order and
     * stops after the one named {@code log} — which is where SLF4J's builder puts the message and
     * its arguments. Returns -1 when the chain does not reach a {@code .log(...)} in this source,
     * so the caller records the site as unparsed rather than reporting a truncated argument list
     * as complete.
     */
    private static int endOfFluentChain(String code, int afterLevel) {
        int i = afterLevel + 1
        while (true) {
            while (i <code.length() && Character.isWhitespace(code.charAt(i))) {
                i++
            }
            if (i >= code.length() || code.charAt(i) != ('.' as char)) {
                return -1
            }
            int nameStart = ++i
            while (i <code.length() && (Character.isLetterOrDigit(code.charAt(i) as char)
                    || code.charAt(i) == ('_' as char))) {
                i++
            }
            String name = code.substring(nameStart, i)
            while (i <code.length() && Character.isWhitespace(code.charAt(i))) {
                i++
            }
            if (i >= code.length() || code.charAt(i) != ('(' as char)) {
                return -1
            }
            int close = closingParen(code, i)
            if (close < 0) {
                return -1
            }
            if (name == 'log') {
                return close
            }
            i = close + 1
        }
    }

    /**
     * Index of the paren closing the one at {@code open}, skipping string and character literals so
     * a message like {@code "still failing ({})"} cannot unbalance the count. Returns -1 when the
     * source does not close it (a comment-stripping artifact), so the caller records that site as
     * unparsed rather than swallowing the rest of the file — or dropping it silently.
     */
    private static int closingParen(String code, int open) {
        int depth = 0
        for (int i = open; i <code.length(); i++) {
            char c = code.charAt(i)
            if (c == '"' as char || c == '\'' as char) {
                i = endOfLiteral(code, i)
                if (i < 0) {
                    return -1
                }
            } else if (c == '(' as char) {
                depth++
            } else if (c == ')' as char && --depth == 0) {
                return i
            }
        }
        -1
    }

    /** Index of the quote closing the literal opened at {@code start}, honouring backslash escapes. */
    private static int endOfLiteral(String code, int start) {
        char quote = code.charAt(start)
        for (int i = start + 1; i <code.length(); i++) {
            char c = code.charAt(i)
            if (c == '\\' as char) {
                i++
            } else if (c == quote) {
                return i
            }
        }
        -1
    }
}
