package com.github.oinsio.gnomish.testsupport

import java.util.regex.Pattern

/**
 * Reads a module's {@code build.gradle} as data, for the whole-tree gates that ask questions about
 * the build itself rather than about sources: {@code ModuleBuildFileSpec} (which leafs stay empty of
 * external artifacts) and {@code DomainLeafPuritySpec} (whether every project the domain reaches
 * really is a JDK-only leaf, FR6 and design D5 of split-logtext-leaves).
 *
 * <p>Both gates need the same two readings — the production-scope dependency declarations, and the
 * {@code layering { allowedProjects }} set — so the parsing lives here once. Every method takes the
 * build file's <em>text</em> as well as a module name, so a gate can seed a violation into a
 * scratch script and prove its own detector fires (the seeded-violation discipline the log-contract
 * and boundary gates use).
 *
 * <p>Comments are stripped through {@link RepoSourceTree#codeOnly} before anything is matched: a
 * build file's comments are dense with quoted module names and apostrophes, and a scan that read
 * them would both invent edges and lose track of its own quoting.
 */
class ModuleBuildFile {

    /** A single-quoted Gradle string literal — how every project path and configuration is spelled. */
    private static final Pattern LITERAL = Pattern.compile("'([^']*)'")

    /** The build file text of one module of the main build, comments removed. */
    static String textOf(String module) {
        def relative = module.replaceFirst(/^:/, '').replace(':', File.separator)
        def file = RepoSourceTree.repoRoot().resolve(relative).resolve('build.gradle').toFile()
        assert file.isFile(): "no build.gradle for module '${module}'"
        RepoSourceTree.code(file)
    }

    /**
     * The non-test dependency declarations of a build file: the body of its {@code dependencies}
     * block minus the {@code test*} configurations, whose scope reaches no consumer. A build file
     * with no {@code dependencies} block declares nothing.
     */
    static Set<String> productionDependencies(String code) {
        blockBody(code, 'dependencies')
                .readLines()
                .collect { it.trim() }
                .findAll { !it.isEmpty() }
                .findAll { !(it =~ /^test\w*\s/) }
                .toSet()
    }

    /** The project paths a build file's {@code layering { allowedProjects }} names, possibly none. */
    static Set<String> allowedProjects(String code) {
        def layering = blockBody(code, 'layering')
        def start = layering.indexOf('allowedProjects')
        if (start < 0) {
            return [] as Set
        }
        def open = layering.indexOf('[' as char as int, start)
        def close = layering.indexOf(']' as char as int, open)
        assert open >= 0 && close> open: 'unbalanced allowedProjects list'
        def found = [] as Set
        def matcher = LITERAL.matcher(layering.substring(open + 1, close))
        while (matcher.find()) {
            found << matcher.group(1)
        }
        found
    }

    /**
     * The text between the braces of a named <em>top-level</em> block, or empty when the build file
     * has none. "Top-level" is read as brace depth zero rather than as a column, so indentation is
     * not what decides it: a nested block of the same name — {@code buildscript { dependencies
     * { ... } }}, which pins a module's plugin classpath — belongs to the buildscript classpath and
     * is not the module's own declaration. A search that took the first textual occurrence would
     * read that inner block instead and, when it is empty, report the module as declaring nothing.
     */
    private static String blockBody(String code, String name) {
        def header = Pattern.compile("(?<![\\w.])${name}\\s*\\{").matcher(code)
        while (header.find()) {
            if (braceDepthBefore(code, header.start()) == 0) {
                return bodyFrom(code, header.end() - 1, name)
            }
        }
        ''
    }

    /** How many braces are still open at {@code end} — zero means the next token is top-level. */
    private static int braceDepthBefore(String code, int end) {
        int depth = 0
        for (int i = 0; i <end; i++) {
            def ch = code.charAt(i)
            if (ch == '{' as char) {
                depth++
            } else if (ch == '}' as char) {
                depth--
            }
        }
        depth
    }

    /** The text between {@code open}'s brace and its match. */
    private static String bodyFrom(String code, int open, String name) {
        def depth = 0
        for (int i = open; i <code.length(); i++) {
            def ch = code.charAt(i)
            if (ch == '{' as char) {
                depth++
            } else if (ch == '}' as char) {
                depth--
                if (depth == 0) {
                    return code.substring(open + 1, i)
                }
            }
        }
        throw new IllegalStateException("unbalanced '${name}' block")
    }
}
