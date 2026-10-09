package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern

/**
 * The stalling-script rule itself (FR2, UX2 of kill-expensive-mutants, design D4), held apart
 * from the gate that runs it: what counts as a script that sleeps, and the scan that finds one.
 *
 * <p>A script sleeps when a Groovy string literal — single, double or triple quoted, GString
 * interpolations included, escapes decoded — holds a shell {@code sleep} word followed by its
 * operand: a number, an expansion ({@code $X}, {@code ${...}}) or {@code infinity}. Code outside
 * literals never counts, so a {@code Thread.sleep(20)} poll loop or a {@code Sleeper} call is not
 * a hit; comments are skipped by the same lexer, so prose about sleeping is not a hit either.
 * {@link StallingGitOwnerSpec} owns the trees and the allowlists and drives this class over seeded
 * sources too, so a lexer that stopped matching cannot pass for a tree that stopped offending.
 */
final class StallingScriptRule {

    /** The shell {@code sleep} word with an operand; a qualified {@code x.sleep} is not a shell word. */
    static final Pattern SHELL_SLEEP = ~/(?<![\w.])sleep\s+["']?(?:\d|\$|infinity)/

    /** The construction every consumer spells: building its stand-in through the owner. */
    static final String OWNER_CONSTRUCTION = 'new StallingGit()'

    private StallingScriptRule() {
    }

    /** Whether any string literal of this file holds a shell sleep. */
    static boolean sleeps(File file) {
        sleepsIn(file.text)
    }

    /** Whether any string literal of this Groovy source holds a shell sleep. */
    static boolean sleepsIn(String source) {
        literals(source).any { SHELL_SLEEP.matcher(it).find() }
    }

    /** Whether this file builds through the owner in code — a javadoc mention is never a hit. */
    static boolean buildsThroughOwner(File file) {
        RepoSourceTree.code(file).contains(OWNER_CONSTRUCTION)
    }

    /** The decoded contents of every string literal of a Groovy source, comments skipped. */
    static List<String> literals(String source) {
        List<String> found = []
        int i = 0
        while (i <source.length()) {
            if (source.startsWith('//', i)) {
                i = endOf(source, '\n', i)
            } else if (source.startsWith('/*', i)) {
                i = endOf(source, '*/', i + 2)
            } else if (source.charAt(i) == '\'' as char || source.charAt(i) == '"' as char) {
                def content = new StringBuilder()
                i = readString(source, i, content)
                found << content.toString()
            } else {
                i++
            }
        }
        found
    }

    private static int endOf(String source, String terminator, int from) {
        int at = source.indexOf(terminator, from)
        at < 0 ? source.length() : at + terminator.length()
    }

    /**
     * Reads the literal opening at {@code start} into {@code content} and returns the index past
     * its closing delimiter. A {@code ${...}} interpolation is copied as written, with braces and
     * nested literals tracked so a quote inside it does not end the outer literal.
     */
    private static int readString(String source, int start, StringBuilder content) {
        char quote = source.charAt(start)
        String delimiter = source.startsWith("${quote}${quote}${quote}", start) ? "${quote}${quote}${quote}" : "${quote}"
        int i = start + delimiter.length()
        while (i <source.length() && !source.startsWith(delimiter, i)) {
            char c = source.charAt(i)
            if (c == '\n' as char && delimiter.length() == 1) {
                // a one-line literal cannot span lines: a stray quote (a slashy regex) ends here
                return i
            } else if (c == '\\' as char && i + 1 <source.length()) {
                content.append(unescape(source.charAt(i + 1)))
                i += 2
            } else if (quote == '"' as char && source.startsWith('${', i)) {
                i = readInterpolation(source, i, content)
            } else {
                content.append(c)
                i++
            }
        }
        Math.min(i + delimiter.length(), source.length())
    }

    private static int readInterpolation(String source, int start, StringBuilder content) {
        content.append('$')
        int depth = 0
        int i = start + 1
        while (i <source.length()) {
            char c = source.charAt(i)
            if (c == '\'' as char || c == '"' as char) {
                int end = readString(source, i, new StringBuilder())
                content.append(source, i, end)
                i = end
                continue
            }
            content.append(c)
            i++
            depth += c == '{' as char ? 1 : c == '}' as char ? -1 : 0
            if (depth == 0) {
                break
            }
        }
        i
    }

    private static String unescape(char escaped) {
        escaped == 'n' as char ? '\n' : escaped == 't' as char ? '\t' : String.valueOf(escaped)
    }
}
