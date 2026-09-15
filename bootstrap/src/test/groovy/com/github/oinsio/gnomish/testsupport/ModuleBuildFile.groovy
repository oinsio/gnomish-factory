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
        blockBody(code, 'dependencies {')
                .readLines()
                .collect { it.trim() }
                .findAll { !it.isEmpty() }
                .findAll { !(it =~ /^test\w*\s/) }
                .toSet()
    }

    /** The project paths a build file's {@code layering { allowedProjects }} names, possibly none. */
    static Set<String> allowedProjects(String code) {
        def layering = blockBody(code, 'layering {')
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

    /** The text between the braces of a named block, or empty when the build file has none. */
    private static String blockBody(String code, String header) {
        def start = code.indexOf(header)
        if (start < 0) {
            return ''
        }
        def open = code.indexOf('{' as char as int, start)
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
        throw new IllegalStateException("unbalanced '${header}' block")
    }
}
