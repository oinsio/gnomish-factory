package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.ModuleBuildFile
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate
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
    private static final int KNOWN_BUILD_FILES = 11

    // FR6: no build file grows back into a monolith — convention plugins are what absorb the bulk
    def "every build file is within the project file-size cap"() {
        given: 'every Gradle script in the build, module build files and convention plugins alike'
        def oversized = gradleScripts()
                .collectEntries {
                    [(RepoSourceTree.relative(it)): it.readLines().size()]
                }
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
            RepoSourceTree.relative(it)
        }
        without.isEmpty()
    }

    // M2: the former single root module holds no source at all — :bootstrap is the root remainder
    def "the root project is build-wide metadata only"() {
        given: 'the repository root'
        def root = RepoSourceTree.repoRoot()

        expect: 'no production or test source tree remains beside the root build file'
        !Files.exists(root.resolve('src'))

        and: 'its build file compiles nothing — no java plugin, no dependencies'
        def rootBuildFile = root.resolve('build.gradle').toFile().text
        !(rootBuildFile =~ /id 'java(-library)?'/)
        !rootBuildFile.contains('dependencies {')
    }

    /**
     * The shared leaf modules whose emptiness is load-bearing, each mapped to the exact set of
     * production-scope dependency declarations its build file may carry. {@code :subprocess} and
     * {@code :atomicfile} declare nothing at all, and so do {@code :baseref}, {@code
     * :untrustedtext} and {@code :operatorevent}, whose emptiness is the constructive half of a
     * security property (NFR-S3 of add-base-ref-resolution, NFR-S1 of split-logtext-leaves): a
     * policy that can import nothing cannot reach a tracker, a remote or a configuration format.
     * {@code :logtext} declares the SLF4J API and the BOM that pins it — MDC propagation is not
     * expressible without the API, while a logging <em>backend</em> stays the composition root's
     * choice — plus the one internal edge FR7 of split-logtext-leaves grants it, to the
     * untrusted-text leaf its {@code LogText} facade delegates to (module-layering scenario "The
     * logtext leaf carries only the logging API"). The internal half of each invariant is gated by
     * {@code layering { allowedProjects } }; this map is the external half.
     *
     * <p>This map is a named registry: it pins the leafs whose emptiness the layering depends on,
     * whether or not anything reaches them today. {@link DomainLeafPuritySpec} asks the companion
     * question from the other end — every project {@code :domain}'s allowlist names must satisfy
     * the same definition, read from the allowlist rather than from a list kept here — so a leaf
     * dropped from this map while the domain still reaches it stays gated there.
     */
    private static final Map<String, Set<String>> LEAF_PRODUCTION_DEPENDENCIES = [
        logtext: [
            "implementation project(':untrustedtext')",
            'implementation platform(libs.spring.boot.dependencies)',
            'api libs.slf4j.api'
        ] as Set,
        subprocess: [] as Set,
        atomicfile: [] as Set,
        baseref: [] as Set,
        untrustedtext: [] as Set,
        operatorevent: [] as Set,
    ]

    /**
     * The internal half of the same registry: the project edges a leaf may declare. Only
     * {@code :logtext} has one — the untrusted-text leaf its {@code LogText} facade delegates to
     * (FR7 of split-logtext-leaves); every other leaf reaches nothing internal at all.
     */
    private static final Map<String, Set<String>> LEAF_ALLOWED_PROJECTS = [
        logtext: [':untrustedtext'] as Set,
    ]

    // FR6: a shared leaf stays consumable from every layer only while it drags nothing behind it,
    // so its permitted external edges are enumerated by this gate rather than described in a comment
    def "the shared leaf #module declares only its permitted production dependencies"() {
        given: 'the module build file, read as data'
        def code = ModuleBuildFile.textOf(module)

        expect: 'the build file really exists and was parsed, so the scan is not vacuous'
        !code.isEmpty()

        and: 'its production-scope declarations are exactly the permitted set'
        ModuleBuildFile.productionDependencies(code) == permitted

        and: 'and the internal half holds too: no leaf reaches a project but the one FR7 grants'
        ModuleBuildFile.allowedProjects(code) == LEAF_ALLOWED_PROJECTS.getOrDefault(module, [] as Set)

        where:
        module << LEAF_PRODUCTION_DEPENDENCIES.keySet()
        permitted << LEAF_PRODUCTION_DEPENDENCIES.values()
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

    private static List<File> walk(Predicate<Path> matching) {
        Files.walk(RepoSourceTree.repoRoot()).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .filter(matching)
            .filter {
                !it.toString().contains("${File.separator}build${File.separator}")
            }
            .map { it.toFile() }
            .toList()
        }
    }
}
