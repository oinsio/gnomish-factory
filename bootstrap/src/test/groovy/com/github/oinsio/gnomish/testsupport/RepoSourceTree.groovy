package com.github.oinsio.gnomish.testsupport

import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate

/**
 * Shared repository-tree scanning for whole-tree architecture gates (see
 * ProjectIdentityDerivationGateSpec, DiscoveredRegistryOnlySpec, GithubPluginAbsenceSpec,
 * ModuleBuildFileSpec, ApiCompatibilityGateSpec): every one of them resolves the same {@code
 * repoRoot} system property and scans the same {@code src/main} tree, so the scan lives here once
 * instead of once per gate.
 */
class RepoSourceTree {

    /** A mis-resolved repoRoot would make a source-tree gate pass over an empty file set. */
    static final int KNOWN_PRODUCTION_SOURCES = 100

    /** The repository root wired by bootstrap's {@code test} task (see verification.gradle). */
    static Path repoRoot() {
        def root = Path.of(System.getProperty('repoRoot'))
        assert Files.isDirectory(root): 'repoRoot system property is not set (see bootstrap/verification.gradle)'
        root
    }

    /** Every production source of the build, optionally narrowed by a relative-path predicate. */
    static List<File> productionSources(Predicate<String> extraFilter = {
                true
            }) {
        Files.walk(repoRoot()).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .map { repoRoot().relativize(it).toString() }
            .filter { it.contains('/src/main/') }
            .filter { it.endsWith('.java') || it.endsWith('.groovy') }
            .filter { extraFilter.test(it) }
            .map { repoRoot().resolve(it).toFile() }
            .toList()
        }
    }

    /** A mis-resolved repoRoot would make a test-source gate pass over an empty file set. */
    static final int KNOWN_TEST_SOURCES = 100

    /**
     * Every test source of the build, optionally narrowed by a relative-path predicate. The log
     * contract gate (FR16) needs it: "a code no test source names" is a whole-tree question about
     * the test tree, the mirror of the production scan above.
     */
    static List<File> testSources(Predicate<String> extraFilter = {
                true
            }) {
        Files.walk(repoRoot()).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .map { repoRoot().relativize(it).toString() }
            .filter { it.contains('/src/test/') }
            .filter { it.endsWith('.java') || it.endsWith('.groovy') }
            .filter { extraFilter.test(it) }
            .map { repoRoot().resolve(it).toFile() }
            .toList()
        }
    }

    /** A file's source with every comment removed: what the compiler actually sees. */
    static String code(File file) {
        file.readLines()
                .collect { line -> codeOnly(line) }
                .join('\n')
    }

    /**
     * A line's source with its comment removed: what the compiler actually sees.
     *
     * <p>The {@code //} that opens a comment is found outside string and character literals only.
     * A blind {@code replaceFirst('//.*', '')} would cut a line like {@code log.warn("see
     * https://host {}", x)} in the middle of its literal, leaving an unbalanced quote that the
     * call-site parser cannot close — and a gate scanning what is left would silently drop the
     * site instead of judging it.
     */
    static String codeOnly(String line) {
        def trimmed = line.trim()
        if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) {
            return ''
        }
        line.substring(0, commentStart(line))
    }

    /** Index of the {@code //} that opens this line's trailing comment, or the line's length. */
    private static int commentStart(String line) {
        int quote = -1
        for (int i = 0; i <line.length(); i++) {
            int c = line.charAt(i) as int
            if (quote >= 0) {
                if (c == ('\\' as char) as int) {
                    i++
                } else if (c == quote) {
                    quote = -1
                }
            } else if (c == ('"' as char) as int || c == ('\'' as char) as int) {
                quote = c
            } else if (c == ('/' as char) as int && i + 1 <line.length() && line.charAt(i + 1) == '/' as char) {
                return i
            }
        }
        line.length()
    }

    /** A file's path relative to the repository root. */
    static String relative(File file) {
        repoRoot().relativize(file.toPath()).toString()
    }
}
