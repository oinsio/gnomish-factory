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

    /** A file's source with every comment removed: what the compiler actually sees. */
    static String code(File file) {
        file.readLines()
                .collect { line -> codeOnly(line) }
                .join('\n')
    }

    /** A line's source with its comment removed: what the compiler actually sees. */
    static String codeOnly(String line) {
        def trimmed = line.trim()
        trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')
                ? ''
                : line.replaceFirst('//.*', '')
    }

    /** A file's path relative to the repository root. */
    static String relative(File file) {
        repoRoot().relativize(file.toPath()).toString()
    }
}
