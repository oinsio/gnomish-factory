package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import spock.lang.Shared
import spock.lang.Specification

/**
 * The one adapter-to-adapter edge pass 2 keeps, held to exactly what it is declared for (FR2, UX2,
 * M4, design D1/D5 of split-into-modules).
 *
 * <p>This is what {@code AdapterSiblingIsolationSpec} became at task 10.2. That rule stood in for
 * Gradle while every adapter shared one module: it enumerated the cross-seam package edges and let
 * the list only shrink. Now each seam D1 names is a real module — {@code :adapters:github}, {@code
 * :adapters:git}, {@code :adapters:agent} — and none of them is on another's compile classpath, so
 * the compiler enforces what the enumeration used to. Every edge the old rule tracked is gone:
 * {@code tracker -> tracker.github} moved to the composition root with the registry that held it
 * (task 10.1), {@code pipeline -> agent} was removed by moving {@code AgentSettingsValidator} to
 * the loader's own package, and {@code agent -> environment} / {@code git -> environment} became
 * ordinary adapter-to-backend edges when task 6.1 carved {@code :sandbox:docker}.
 *
 * <p>What remains is a single edge that Gradle can only state at module granularity: this module
 * depends on {@code :adapters}, the coarse remainder, for the frozen pipeline law and the briefing
 * renderer — D1's sanctioned "`:adapters:agent` depends on the coarse module" option, chosen
 * because {@code adapter.law} and {@code adapter.briefing} are shared with the console and would be
 * misfiled inside the agent executor. A module dependency, though, buys reach into every coarse
 * package, so this rule narrows it back to the two the build file declares it for. The reverse
 * direction needs no rule: {@code :adapters} does not depend on this module at all.
 */
class AgentCoarseReachSpec extends Specification {

    private static final String ADAPTER = 'com.github.oinsio.gnomish.adapter.'

    /** The coarse packages this module is declared to reach. */
    private static final Set<String> ALLOWED_COARSE_PACKAGES = ['law', 'briefing'] as Set

    /** The packages this module owns; everything else under {@code ..adapter} is coarse. */
    private static final Set<String> OWN_PACKAGES = ['agent'] as Set

    /**
     * This module's own compiled production bytecode. {@code DO_NOT_INCLUDE_JARS} matters as much
     * as {@code DO_NOT_INCLUDE_TESTS}: the coarse module and the shared fixtures both reach this
     * classpath as jars, in the very packages the rule selects on. The gate constrains this
     * module's production classes only.
     */
    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
    .importPackages('com.github.oinsio.gnomish.adapter')

    def "FR2/M4: the agent adapter reaches only the coarse packages it is declared for"() {
        given: 'the reach rule'
        def rule = classes()
                .that()
                .resideInAPackage('com.github.oinsio.gnomish.adapter..')
                .should(reachOnlyDeclaredCoarsePackages())

        expect: 'the rule holds over this module\'s production classes (check throws on violation)'
        rule.check(productionClasses)
    }

    def "the declared reach is exact: every allowed coarse package is really used"() {
        given: 'the coarse packages actually reached from this module\'s bytecode'
        Set<String> reached = []
        productionClasses.each { source ->
            source.directDependenciesFromSelf.each { dependency ->
                String target = coarsePackageOf(dependency.targetClass)
                if (target != null) {
                    reached.add(target)
                }
            }
        }

        expect: 'no entry has outlived the edge it documents — a stale allowance hides a regression'
        (ALLOWED_COARSE_PACKAGES - reached).isEmpty()
    }

    /** Violated by any dependency on a coarse adapter package outside the declared set. */
    private static ArchCondition<JavaClass> reachOnlyDeclaredCoarsePackages() {
        new ArchCondition<JavaClass>('reach only the coarse adapter packages this module declares') {

                    @Override
                    void check(JavaClass source, ConditionEvents events) {
                        source.directDependenciesFromSelf.each { dependency ->
                            String target = coarsePackageOf(dependency.targetClass)
                            if (target != null && !ALLOWED_COARSE_PACKAGES.contains(target)) {
                                events.add(SimpleConditionEvent.violated(
                                                dependency,
                                                "${source.name} reaches ${dependency.targetClass.name} in the coarse "
                                                + "adapter package '${target}', which `:adapters:agent` does not "
                                                + "declare (allowed: ${ALLOWED_COARSE_PACKAGES.sort().join(', ')}). "
                                                + 'The module dependency on `:adapters` is for the pipeline law and '
                                                + 'the briefing renderer only — anything else has to go through a '
                                                + 'port first (FR2/M4).'))
                            }
                        }
                    }
                }
    }

    /** The coarse adapter package a target sits in, or {@code null} if it is this module's or not an adapter. */
    private static String coarsePackageOf(JavaClass type) {
        if (!type.packageName.startsWith(ADAPTER)) {
            return null
        }
        String pkg = type.packageName.substring(ADAPTER.length())
        String root = pkg.contains('.') ? pkg.substring(0, pkg.indexOf('.')) : pkg
        OWN_PACKAGES.contains(root) ? null : root
    }
}
