package com.github.oinsio.gnomish.testsupport

import groovy.transform.Canonical

import java.util.function.Predicate
import java.util.regex.Pattern

/**
 * Java-source call-site extraction for the convention gates: delimiting a call's parentheses past
 * the string literals inside it, and splitting the delimited group into its top-level arguments.
 *
 * <p>It exists because a second family of gates needs the same parser {@link LogCallSites} built
 * for SLF4J calls. The untrusted-text sink gate judges four call shapes — a log call, a throwable
 * constructor, a console print, a tracker write — and one question about each: is some argument of
 * this call, at the top level of its own argument list, an untrusted-text carrier? So the
 * mechanics live here once and {@code LogCallSites} delimits through them too, rather than the two
 * gates holding two parsers that would drift.
 *
 * <p>Every method takes source with comments already removed ({@link RepoSourceTree#code}): a
 * convention gate judges what the compiler sees.
 */
class JavaCallSites {

    /** One call site: where it is, its whole text, and the arguments it passes at its top level. */
    @Canonical
    static class Call {
        String path
        int line
        String text
        List<String> arguments
    }

    /**
     * Every call in one source whose head matches {@code head}, which must end at the call's
     * opening parenthesis. A call whose parentheses this source does not close is skipped — the
     * caller that cares reports those separately, as {@code LogCallSites} does.
     */
    static List<Call> callsOf(String code, String path, Pattern head) {
        def calls = []
        def matcher = head.matcher(code)
        while (matcher.find()) {
            int open = matcher.end() - 1
            int close = closingParen(code, open)
            if (close < 0) {
                continue
            }
            int line = code.take(matcher.start()).count('\n') + 1
            calls << new Call(path, line, code.substring(matcher.start(), close + 1), arguments(code, open))
        }
        calls
    }

    /** Every production source's calls matching {@code head}, optionally narrowed by path. */
    static List<Call> productionCallsOf(Pattern head, Predicate<String> filter = {
                true
            }) {
        RepoSourceTree.productionSources(filter).collectMany { file ->
            callsOf(RepoSourceTree.code(file), RepoSourceTree.relative(file), head)
        }
    }

    /**
     * The arguments of the group opened at {@code open}, split on the commas that belong to this
     * call rather than to a nested one: an argument like {@code Map.of(a, b)} is one argument, and
     * the whole point of the gate is that {@code LogText.forLog(x.stderr())} is one too.
     */
    static List<String> arguments(String code, int open) {
        int close = closingParen(code, open)
        if (close < 0) {
            return []
        }
        def arguments = []
        int depth = 0
        int start = open + 1
        for (int i = start; i <close; i++) {
            char c = code.charAt(i)
            if (c == '"' as char || c == '\'' as char) {
                i = endOfLiteral(code, i)
                if (i < 0) {
                    return []
                }
            } else if (c in [
                        '(' as char,
                        '[' as char,
                        '{' as char
                    ]) {
                depth++
            } else if (c in [
                        ')' as char,
                        ']' as char,
                        '}' as char
                    ]) {
                depth--
            } else if (c == ',' as char && depth == 0) {
                arguments << code.substring(start, i).trim()
                start = i + 1
            }
        }
        def last = code.substring(start, close).trim()
        if (!last.isEmpty()) {
            arguments << last
        }
        arguments
    }

    /**
     * The arguments every segment of a chained call passes, for a call text that may carry them
     * further down a builder chain than its own parentheses — SLF4J's fluent form
     * ({@code log.atWarn().setMessage(m).addArgument(x).log()}) is the reason. Only groups the
     * chain itself opens are read: a nested call's arguments are that call's, not this one's, so
     * {@code String.valueOf(carrier)} is a string argument here, exactly as the compiler sees it.
     */
    static List<String> chainArguments(String callText) {
        def collected = []
        int depth = 0
        for (int i = 0; i <callText.length(); i++) {
            char c = callText.charAt(i)
            if (c == '"' as char || c == '\'' as char) {
                i = endOfLiteral(callText, i)
                if (i < 0) {
                    return collected
                }
            } else if (c == '(' as char) {
                if (depth == 0) {
                    collected.addAll(arguments(callText, i))
                }
                depth++
            } else if (c == ')' as char) {
                depth--
            }
        }
        collected
    }

    /**
     * Index of the paren closing the one at {@code open}, skipping string and character literals so
     * a message like {@code "still failing ({})"} cannot unbalance the count. Returns -1 when the
     * source does not close it (a comment-stripping artifact), so the caller records that site as
     * unparsed rather than swallowing the rest of the file — or dropping it silently.
     */
    static int closingParen(String code, int open) {
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
    static int endOfLiteral(String code, int start) {
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
