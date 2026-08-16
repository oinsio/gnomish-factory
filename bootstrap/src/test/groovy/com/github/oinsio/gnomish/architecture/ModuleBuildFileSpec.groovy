package com.github.oinsio.gnomish.architecture

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * Build-file gate (FR6, M2, task 9.2 of split-into-modules). The monolithic 796-line
 * {@code build.gradle} was replaced by {@code build-logic} convention plugins plus thin per-module
 * build files; this keeps it that way.
 *
 * <p>Whole-tree gates live in {@code :bootstrap} because it is the one module that sees every layer
 * at once, and the build files are the same kind of whole-tree subject as
 * {@link SecretsPortBoundarySpec}'s credential scan — which is also what wires the {@code repoRoot}
 * system property and declares the build metadata as an input of this module's {@code test} task,
 * so editing a build script really does re-run this gate.
 */
class ModuleBuildFileSpec extends Specification {

    /** The project's hard file-size cap (.claude/rules/process-invariants.md); the target is 100-120. */
    private static final int LINE_CAP = 200

    /** A mis-resolved repoRoot would make every scenario below pass over an empty file set. */
    private static final int KNOWN_BUILD_FILES = 10

    // FR6: no build file grows back into a monolith — convention plugins are what absorb the bulk
    def "every build file is within the project file-size cap"() {
        given: 'every Gradle script in the build, module build files and convention plugins alike'
        def oversized = gradleScripts()
                .collectEntries { [(relative(it)): it.readLines().size()] }
                .findAll { _, lines -> lines> LINE_CAP }

        expect: 'none exceeds the cap'
        oversized.isEmpty()
    }

    // M2: the bulk lives in build-logic, so a module build file states only what that module is
    def "every module build file applies a build-logic convention plugin"() {
        given: 'the build files of the main build (build-logic is its own included build)'
        def files = moduleBuildFiles()

        expect: 'the scan really reached the module tree'
        files.size() >= KNOWN_BUILD_FILES

        and: 'each declares at least one convention plugin id'
        def without = files.findAll {
            !(it.text =~ /id '[\w-]+-conventions'/)
        }.collect {
            relative(it)
        }
        without.isEmpty()
    }

    // M2: the former single root module holds no source at all — :bootstrap is the root remainder
    def "the root project is build-wide metadata only"() {
        given: 'the repository root'
        def root = repoRoot()

        expect: 'no production or test source tree remains beside the root build file'
        !Files.exists(root.resolve('src'))

        and: 'its build file compiles nothing — no java plugin, no dependencies'
        def rootBuildFile = root.resolve('build.gradle').toFile().text
        !(rootBuildFile =~ /id 'java(-library)?'/)
        !rootBuildFile.contains('dependencies {')
    }

    private static Path repoRoot() {
        def root = Path.of(System.getProperty('repoRoot'))
        assert Files.isDirectory(root): 'repoRoot system property is not set (see bootstrap/verification.gradle)'
        root
    }

    /** Every Gradle script in the repository, skipping build outputs. */
    private static List<File> gradleScripts() {
        walk { it.fileName.toString().endsWith('.gradle') }
    }

    /** The main build's project build files; `build-logic` is a separate included build. */
    private static List<File> moduleBuildFiles() {
        walk {
            it.fileName.toString() == 'build.gradle' && !it.toString().contains("build-logic${File.separator}")
        }
    }

    private static List<File> walk(java.util.function.Predicate<Path> matching) {
        Files.walk(repoRoot()).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .filter(matching)
            .filter {
                !it.toString().contains("${File.separator}build${File.separator}")
            }
            .map { it.toFile() }
            .toList()
        }
    }

    private static String relative(File file) {
        repoRoot().relativize(file.toPath()).toString()
    }
}
