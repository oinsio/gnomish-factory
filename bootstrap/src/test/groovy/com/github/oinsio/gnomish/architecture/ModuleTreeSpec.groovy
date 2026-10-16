package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.ModuleBuildFile
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import spock.lang.Specification

/**
 * The module tree as the build declares it (FR1, FR2, FR8, FR10 of split-into-modules):
 * which projects {@code settings.gradle} includes, and which edges between
 * them the module build files declare. Read as data from {@code repoRoot}, like
 * {@link ModuleBuildFileSpec}; the transitive half of the layering is {@code verifyModuleLayering}'s.
 */
class ModuleTreeSpec extends Specification {

    /** The projects the module-layering capability names, each a separate project of the build. */
    private static final List<String> NAMED_PROJECTS = [
        ':domain',
        ':subprocess',
        ':atomicfile',
        ':untrustedtext',
        ':operatorevent',
        ':operatorconfig',
        ':logtext',
        ':gitobjects',
        ':gittransfer',
        ':baseref',
        ':gnomish-plugin-api',
        ':application',
        ':adapters',
        ':adapters:github',
        ':adapters:git',
        ':adapters:agent',
        ':sandbox:core',
        ':sandbox:docker',
        ':bootstrap'
    ]

    /** The three vertical adapter modules; none may declare another. */
    private static final List<String> VERTICAL_ADAPTERS = [
        ':adapters:github',
        ':adapters:git',
        ':adapters:agent'
    ]

    // FR1 of split-into-modules: every layer is its own Gradle project with its own build file, and
    //     the former single root module keeps no production source
    // Scenario: build-system/module-layering / Modules resolve as distinct Gradle projects
    def "every named module is a separate project of the build, and the root holds no source"() {
        given:
        def included = ModuleBuildFile.includedProjects()

        expect: 'each named project is included and has a build file of its own'
        NAMED_PROJECTS.findAll {
            !(it in included) || ModuleBuildFile.textOf(it).isEmpty()
        } == []

        and: 'no production Java class remains in the root module'
        !Files.exists(RepoSourceTree.repoRoot().resolve('src'))
    }

    // FR10 of split-into-modules: the github bundle is one module, and its shared HTTP core is a
    //     package inside it rather than a module another adapter could depend on
    // Scenario: build-system/module-layering / The github vendor bundle is one module
    def "the github bundle is one module whose HTTP core no other production module can reach"() {
        given:
        def coreSources = RepoSourceTree.productionSources {
            it.contains('/com/github/oinsio/gnomish/adapter/github/')
        }

        expect: 'the three vertical adapters are separate projects'
        ModuleBuildFile.includedProjects().containsAll(VERTICAL_ADAPTERS)

        and: 'the shared HTTP core is a package of :adapters:github only'
        !coreSources.isEmpty()
        coreSources.every {
            RepoSourceTree.relative(it).startsWith('adapters/github/src/main/')
        }

        and: 'only the composition root declares the github module on a production classpath'
        declaringModules("project(':adapters:github')") == [':bootstrap']
    }

    // FR2, FR10 of split-into-modules: an undeclared sibling is absent from the compile classpath, so
    //     no vertical adapter declares another — an import of its internals cannot compile
    // Scenario: build-system/module-layering / Adapter importing a sibling adapter's internals fails the build
    // Scenario: build-system/module-layering / Sibling isolation holds after the vertical split
    def "#adapter declares no sibling adapter module, neither as a dependency nor in its layering allowlist"() {
        given:
        def code = ModuleBuildFile.textOf(adapter)
        def siblings = VERTICAL_ADAPTERS - adapter

        expect:
        siblings.findAll { sibling ->
            code.contains("project('${sibling}')")
        } == []
        (ModuleBuildFile.allowedProjects(code).intersect(siblings)) == [] as Set

        where:
        adapter << VERTICAL_ADAPTERS
    }

    // FR8 of split-into-modules: the backend builds on the core, and the core names no backend
    // Scenario: build-system/sandbox-module-split / Backend depends on core, not the reverse
    def "the docker backend depends on the sandbox core, and the core on no backend"() {
        given:
        def docker = ModuleBuildFile.textOf(':sandbox:docker')
        def core = ModuleBuildFile.textOf(':sandbox:core')

        expect:
        ModuleBuildFile.productionDependencies(docker).any {
            it.contains("project(':sandbox:core')")
        }
        ':sandbox:core' in ModuleBuildFile.allowedProjects(docker)

        and: 'the core reaches nothing under :sandbox but itself'
        !ModuleBuildFile.allowedProjects(core).any {
            it.startsWith(':sandbox:')
        }
        !core.contains("project(':sandbox:docker')")
    }

    /** The modules whose build file spells {@code declaration} outside a {@code test*} configuration. */
    private static List<String> declaringModules(String declaration) {
        ModuleBuildFile.includedProjects().findAll { module ->
            ModuleBuildFile.productionDependencies(ModuleBuildFile.textOf(module)).any {
                it.contains(declaration)
            }
        }
    }
}
