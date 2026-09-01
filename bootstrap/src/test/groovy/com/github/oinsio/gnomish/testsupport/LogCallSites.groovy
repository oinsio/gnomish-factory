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

    private static final Pattern LOG_CALL =
    Pattern.compile('\\b(?:log|logger|LOG|LOGGER)\\.(?:error|warn|info|debug|trace)\\s*\\(')

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
        def calls = []
        def matcher = LOG_CALL.matcher(code)
        while (matcher.find()) {
            int close = closingParen(code, matcher.end() - 1)
            if (close < 0) {
                continue
            }
            calls << new LogCall(path, code.take(matcher.start()).count('\n') + 1,
                    code.substring(matcher.start(), close + 1))
        }
        calls
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
     * Index of the paren closing the one at {@code open}, skipping string and character literals so
     * a message like {@code "still failing ({})"} cannot unbalance the count. Returns -1 when the
     * source does not close it (a comment-stripping artifact), so the caller skips that site
     * rather than swallowing the rest of the file.
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
