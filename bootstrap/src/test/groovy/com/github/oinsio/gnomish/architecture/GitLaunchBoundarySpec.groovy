package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.architecture.launchseed.ExtraSpawnSeed
import com.github.oinsio.gnomish.architecture.launchseed.OwnerSkippingLauncherSeed
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR7, design D7 of own-git-invocation-policy: the invocation policy has one owner, {@code
 * GitInvocation.commandLine} in {@code :gittransfer}, and no bypass. Over every production class
 * of the build (bytecode, through ArchUnit), the set of classes that depend on {@link
 * ProcessBuilder} equals the allowlist below, each entry with its reason — equality, not a subset,
 * so a new spawn site fails until it is classified, and an entry whose class stopped building a
 * process fails as stale. Both git launchers must call the owner. Nested, inner and lambda classes
 * ({@code Outer$Inner}) count as their top-level class, the unit the allowlist names.
 *
 * <p>This gate asks "who builds a command line" and nothing else. Who may call {@code start()} is
 * {@link LaunchOwnerGateSpec}'s question (the launch owner alone, FR18 and NG14 of
 * fix-terminal-receipt-convergence); which packages may touch {@code ProcessBuilder} at all is
 * {@link ProcessSpawnBoundarySpec}'s. The three gates overlap on no assertion.
 *
 * <p>Over a clean tree both detectors report exactly what a broken detector could also report, so
 * the seeded features point them at classes written to break them ({@code architecture.launchseed};
 * {@link GitTransferBoundarySpec} is the model).
 */
class GitLaunchBoundarySpec extends Specification {

    private static final String PROCESS_BUILDER = 'java.lang.ProcessBuilder'

    private static final String OWNER = 'com.github.oinsio.gnomish.gittransfer.GitInvocation'

    private static final String OWNER_METHOD = 'commandLine'

    private static final String SEED_PACKAGE = 'com.github.oinsio.gnomish.architecture.launchseed'

    /** The two git launchers, the only builders of a git command line; each must call the owner. */
    private static final Map<String, String> GIT_LAUNCHERS = [
        'com.github.oinsio.gnomish.adapter.git.GitProcessRunner': 'the git launcher of the factory clone (:adapters:git)',
        'com.github.oinsio.gnomish.gitobjects.GitExec' : 'the git launcher of the bare-object writer (:gitobjects)',
    ]

    /** Every production class allowed to depend on ProcessBuilder, each with its reason (D7). */
    private static final Map<String, String> SPAWN_SITES = GIT_LAUNCHERS + [
        'com.github.oinsio.gnomish.sandbox.environment.DockerCli' : 'the docker client (:sandbox:docker)',
        'com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment': 'gnome-product processes, not factory git (:sandbox:docker)',
        'com.github.oinsio.gnomish.sandbox.StderrPolicy' : 'applies a stderr redirect to a builder an adapter built, constructs none (D18 of fix-terminal-receipt-convergence)',
        'com.github.oinsio.gnomish.subprocess.CaptureRunner' : 'launch-owner helper: takes a caller\'s builder, names no binary (D16 of fix-terminal-receipt-convergence)',
        'com.github.oinsio.gnomish.subprocess.ProcessLaunch' : 'the launch role: starts a caller\'s builder, names no binary (D16, D19 of fix-terminal-receipt-convergence)',
        'com.github.oinsio.gnomish.subprocess.GroupedLaunch' : 'the launch owner: prefixes the launcher and starts, names no binary (D19 of fix-terminal-receipt-convergence)',
    ]

    /** The :test-fixtures module is test support on the production classpath, not production. */
    private static final ImportOption NO_TEST_FIXTURES = { Location location ->
        !location.contains('/test-fixtures/')
    } as ImportOption

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .withImportOption(NO_TEST_FIXTURES)
    .importPackages('com.github.oinsio.gnomish')

    @Shared
    JavaClasses seededClasses = new ClassFileImporter().importPackages(SEED_PACKAGE)

    // FR7: a class outside the allowlist that builds a process fails here, by name; an entry
    //     whose class no longer depends on ProcessBuilder fails too, so the list cannot go stale.
    // Scenario: git/git-invocation-policy / A third launcher fails the build
    def "FR7: the production classes depending on ProcessBuilder are exactly the classified spawn sites"() {
        expect: 'the import reached the owner\'s leaf, so an empty set is not a missed classpath'
        productionClasses.contain(OWNER)

        and: 'the spawn sites the bytecode shows equal the allowlist'
        spawnSites(productionClasses) == SPAWN_SITES.keySet()
    }

    // Scenario: build-system/module-layering / Both git launchers take one invocation policy
    def "FR7: both git launchers take their command line from GitInvocation.commandLine"() {
        expect:
        GIT_LAUNCHERS.keySet().findAll {
            !callsOwner(productionClasses, it)
        } == [] as Set
    }

    // The detector is the gate: a seeded spawn site, its builder held by a nested class, is a
    //     difference from the allowlist named by its top-level class.
    // Scenario: git/git-invocation-policy / A third launcher fails the build
    def "FR7: a seeded extra spawn site is reported under its top-level class"() {
        given: 'the production classes with the seeds beside them'
        def sites = spawnSites(productionClasses + seededClasses)

        expect: 'the seed breaks the equality, and is named by its top-level class'
        sites != SPAWN_SITES.keySet()
        sites - SPAWN_SITES.keySet() == [
            ExtraSpawnSeed.name,
            OwnerSkippingLauncherSeed.name
        ] as Set

        and: 'the nested class that holds the builder is the one the bytecode shows'
        seededClasses.any {
            it.name == ExtraSpawnSeed.name + '$Spawner' && dependsOnProcessBuilder(it)
        }
        !dependsOnProcessBuilder(seededClasses.get(ExtraSpawnSeed))
    }

    def "FR7: a seeded launcher that skips the owner is reported"() {
        expect: 'the seed builds a process, so it is a launcher by the first detector'
        spawnSites(seededClasses).contains(OwnerSkippingLauncherSeed.name)

        and: 'and it does not take its command line from the owner'
        !callsOwner(seededClasses, OwnerSkippingLauncherSeed.name)
    }

    /** The top-level classes, by name, of every class that depends on ProcessBuilder. */
    private static Set<String> spawnSites(Iterable<JavaClass> classes) {
        classes.findAll { dependsOnProcessBuilder(it) }
        .collect { topLevel(it) }
        .toSet()
    }

    private static boolean dependsOnProcessBuilder(JavaClass javaClass) {
        javaClass.directDependenciesFromSelf.any {
            it.targetClass.name == PROCESS_BUILDER
        }
    }

    /** Whether the named top-level class, nested classes included, calls the owner's method. */
    private static boolean callsOwner(Iterable<JavaClass> classes, String topLevelName) {
        classes.findAll { topLevel(it) == topLevelName }.any { javaClass ->
            javaClass.methodCallsFromSelf.any {
                it.targetOwner.name == OWNER && it.name == OWNER_METHOD
            }
        }
    }

    /** {@code a.b.Outer$Inner$1} as {@code a.b.Outer}: the unit the allowlist names. */
    private static String topLevel(JavaClass javaClass) {
        def name = javaClass.name
        int nested = name.indexOf('$')
        nested < 0 ? name : name.substring(0, nested)
    }
}
