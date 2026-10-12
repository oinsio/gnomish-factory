package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.github.oinsio.gnomish.adapter.git.GitTaskBackend
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.AllowedBases
import com.github.oinsio.gnomish.baseref.BaseDecision
import com.github.oinsio.gnomish.baseref.BasePattern
import com.github.oinsio.gnomish.baseref.BaseRefResolver
import com.github.oinsio.gnomish.baseref.DesignatorSelection
import com.github.oinsio.gnomish.testsupport.ModuleBuildFile
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * Which layer a module's production bytecode may reach, read from the compiled classes rather than
 * from the build files (FR2, FR8, FR10 of split-into-modules; FR10, NFR-S3 of
 * add-base-ref-resolution; FR13 of harden-task-branch-contract). {@code :bootstrap} is the one
 * module whose test classpath holds every layer, so the rules see every module at once.
 */
class LayerReachSpec extends Specification {

    private static final String ROOT = 'com.github.oinsio.gnomish'

    /** The packages of each vertical adapter module; none may reach another's. */
    private static final Map<String, List<String>> VERTICAL_PACKAGES = [
        ':adapters:github': [
            "${ROOT}.adapter.github..",
            "${ROOT}.adapter.tracker.github..",
            "${ROOT}.adapter.check.github.."
        ]*.toString(),
        ':adapters:git' : [
            "${ROOT}.adapter.git..".toString()
        ],
        ':adapters:agent' : [
            "${ROOT}.adapter.agent..".toString()
        ],
    ]

    /** The JDK packages a pure policy leaf may not touch: the filesystem and the network. */
    private static final String[] IO_PACKAGES = [
        'java.io..',
        'java.nio.file..',
        'java.net..'
    ]

    /** The JDK types that start or reach a subprocess. */
    private static final List<String> PROCESS_TYPES = [
        'java.lang.ProcessBuilder',
        'java.lang.Process',
        'java.lang.Runtime'
    ]

    /** Production bytecode of every module; the shared fixtures' jar is test code and stays out. */
    @Shared
    JavaClasses production = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .withImportOption({ location ->
        !location.contains('/test-fixtures/')
    } as ImportOption)
    .importPackages(ROOT)

    // FR10, NFR-S3 of add-base-ref-resolution: the policy is values in, decision out
    // Scenario: build/module-layering / The base-resolution policy is a pure function in baseref
    def "the base-resolution policy types come from :baseref, which reaches no subprocess, port or factory type"() {
        expect: 'grammar, validation, priority and decision are the leaf\'s own types'
        [
            BasePattern,
            AllowedBases,
            DesignatorSelection,
            BaseRefResolver,
            BaseDecision
        ].every {
            it.protectionDomain.codeSource.location.path.contains('/baseref/')
        }

        and: 'the leaf depends on the JDK, itself and the text leaf only'
        classes().that().resideInAPackage("${ROOT}.baseref..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage('java..', "${ROOT}.baseref..", "${ROOT}.untrustedtext..", 'org.jspecify..')
                .check(production)

        and: 'and touches no process, file or network API of the JDK'
        noClasses().that().resideInAPackage("${ROOT}.baseref..")
                .should().dependOnClassesThat().resideInAnyPackage(IO_PACKAGES)
                .check(production)
        production.findAll { it.packageName.startsWith("${ROOT}.baseref") }
        .findAll { type ->
            type.directDependenciesFromSelf.any {
                it.targetClass.name in PROCESS_TYPES
            }
        } == []
    }

    // FR13 of harden-task-branch-contract: the one tenure record is published through the contract
    // Scenario: build/module-layering / A vendor adapter reaches the tenure record through the contract
    def "the github adapter reads the tenure through the contract's seam, and no adapter keeps a record"() {
        expect: 'the vendor adapter stamps from the published read-only seam'
        production.get("${ROOT}.adapter.tracker.github.GithubMarkerWriter").directDependenciesFromSelf
                .any { it.targetClass.name == ClaimEpochSource.name }
        ClaimEpochSource.protectionDomain.codeSource.location.path.contains('/gnomish-plugin-api/')

        and: 'no adapter reaches the application-side record behind it — but the task-git assembly'
        noClasses().that().resideInAPackage("${ROOT}.adapter..")
                .and().doNotHaveFullyQualifiedName(GitTaskBackend.name)
                .should().dependOnClassesThat().resideInAPackage("${ROOT}.app.lease..")
                .check(production)

        and: 'which names the book only to put the caller\'s one into the bundle it builds, keeping none (F74 of tighten-spec-contract)'
        production.get(GitTaskBackend.name).fields.isEmpty()

        and: 'and no adapter implements a tenure record of its own'
        noClasses().that().resideInAPackage("${ROOT}.adapter..")
                .should().implement(ClaimEpochSource)
                .check(production)
    }

    // FR2, FR10 of split-into-modules: the vertical adapter modules share no internals
    // Scenario: build/module-layering / Adapter importing a sibling adapter's internals fails the build
    // Scenario: build/module-layering / Sibling isolation holds after the vertical split
    def "no class of #adapter depends on a sibling adapter's packages"() {
        given:
        def siblings = (VERTICAL_PACKAGES.findAll {
            it.key != adapter
        }.values().flatten()) as String[]

        expect: 'the selector reaches classes of the module'
        production.any { type ->
            VERTICAL_PACKAGES[adapter].any {
                packageOf(it) == type.packageName
            }
        }

        and:
        noClasses().that().resideInAnyPackage(VERTICAL_PACKAGES[adapter] as String[])
                .should().dependOnClassesThat().resideInAnyPackage(siblings)
                .check(production)

        where:
        adapter << VERTICAL_PACKAGES.keySet()
    }

    // FR8 of split-into-modules: the core holds the port and the backend-neutral policy only
    // Scenario: build/sandbox-module-split / Core is free of backend-specific code and dependencies
    def "the sandbox core declares and reaches no backend mechanics"() {
        given:
        def core = ModuleBuildFile.productionDependencies(ModuleBuildFile.textOf(':sandbox:core'))

        expect: 'no backend library on the core\'s build'
        core.findAll { it =~ /(?i)docker|testcontainers/ } == [] as Set

        and: 'no core class reaches a backend package or library'
        noClasses().that().resideInAPackage("${ROOT}.sandbox")
                .should().dependOnClassesThat()
                .resideInAnyPackage("${ROOT}.sandbox.environment..", 'org.testcontainers..', 'com.github.dockerjava..')
                .check(production)

        and: 'the backend package lives in the backend module only'
        production.findAll {
            it.packageName.startsWith("${ROOT}.sandbox.environment")
        }
        .every { locationOf(it).contains('/sandbox/docker/') }
    }

    // FR8 of split-into-modules: the use cases name the core's port, never a backend type
    // Scenario: build/sandbox-module-split / Use cases reach the port through core
    def "the application reaches the execution environment through the core, never a backend module"() {
        given:
        def application = production.findAll {
            locationOf(it).contains('/application/')
        }

        expect: 'the application really uses the core\'s port'
        application.any { type ->
            type.directDependenciesFromSelf.any {
                it.targetClass.packageName == "${ROOT}.sandbox".toString()
            }
        }

        and: 'and no application class reaches the backend package'
        application.findAll { type ->
            type.directDependenciesFromSelf.any {
                it.targetClass.packageName.startsWith("${ROOT}.sandbox.environment")
            }
        }*.name == []
    }

    private static String packageOf(String selector) {
        selector.replaceFirst(/\.\.$/, '')
    }

    /** Where a class was loaded from: a module's jar or class folder. */
    private static String locationOf(JavaClass type) {
        type.source.map { it.uri.toString() }.orElse('')
    }
}
