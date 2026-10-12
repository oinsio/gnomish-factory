package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path

/**
 * The single version sources of the security overrides, read for {@link SecurityOverrideLockStateSpec}
 * (FR3, FR4 of fix-osv-dependency-gate): the catalog's security-override block, whose entries pin the
 * catalog modules that reference them, and the {@code gradle.properties} buildscript versions that
 * each {@code build.gradle}'s buildscript block forces. The spec owns what the lock state must say;
 * this class owns only how the two sources are read.
 */
final class SecurityOverrideSources {

    private SecurityOverrideSources() {
    }

    /** The catalog's security-override entries: name → version, the comment above it, the modules pinned by it. */
    static Map<String, Map> catalogOverrides() {
        def lines = catalogLines()
        int start = lines.findIndexOf {
            it.startsWith('# --- Security overrides')
        }
        int end = lines.findIndexOf { it == '[libraries]' }
        assert start >= 0 && end> start: 'the catalog has no security-override block'
        def entries = [:]
        def comment = []
        lines.subList(start, end).each { line ->
            def entry = line =~ /^([\w-]+) = "([^"]+)"/
            if (entry.find()) {
                def name = entry.group(1)
                entries[name] = [version: entry.group(2), comment: comment.join('\n'), modules: modulesOf(lines, name)]
                comment = []
            } else if (line.startsWith('#')) {
                comment << line
            }
        }
        entries
    }

    static List<String> catalogHeader() {
        section(catalogLines(), '# --- Security overrides', 'Drop each once')
    }

    /** {@code group:artifact} → version, for every catalog module pinned by a security override. */
    static Map<String, String> catalogPins() {
        catalogOverrides().collectEntries { name, entry ->
            entry.modules.collectEntries {
                [(it): entry.version]
            }
        }
    }

    static List<String> modulesOf(List<String> lines, String versionName) {
        lines.collect {
            it =~ /module = "([^"]+)", version\.ref = "${versionName}"/
        }
        .findAll { it.find() }
        .collect { it.group(1) }
    }

    /** Each buildscript {@code force} of a {@code gradle.properties} version: module, version, lockfile. */
    static List<Map<String, Object>> buildscriptForces() {
        def versions = Files.readAllLines(root().resolve('gradle.properties'))
                .findAll { it =~ /^buildscript\w+Version=/ }
                .collectEntries { it.split('=', 2) as List }
        [
            'build.gradle',
            'bootstrap/build.gradle'
        ].collectMany { script ->
            def file = root().resolve(script)
            (Files.readString(file) =~ /"([\w.-]+:[\w.-]+):\$\{(buildscript\w+Version)}"/).collect {
                [module: it[1], version: versions[it[2]], lockfile: file.resolveSibling('buildscript-gradle.lockfile')]
            }
        }
    }

    /** Whether the subject of a properties bullet ({@code tools.jackson}, {@code httpcore5}) names the module. */
    static boolean covers(String subject, String module) {
        def (group, artifact) = module.tokenize(':')
        artifact.startsWith(subject.trim()) || group.startsWith(subject.trim())
    }

    static List<String> section(List<String> lines, String first, String last) {
        int from = lines.findIndexOf { it.startsWith(first) }
        int to = lines.findIndexOf(Math.max(from, 0)) { it.contains(last) }
        assert from >= 0 && to >= from: "no section from '${first}' to '${last}'"
        lines.subList(from, to + 1)
    }

    static List<String> catalogLines() {
        Files.readAllLines(root().resolve('gradle/libs.versions.toml'))
    }

    private static Path root() {
        RepoSourceTree.repoRoot()
    }
}
