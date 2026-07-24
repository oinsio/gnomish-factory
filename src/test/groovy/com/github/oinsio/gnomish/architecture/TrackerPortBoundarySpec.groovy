package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import spock.lang.Shared
import spock.lang.Specification

/**
 * Tracker-port boundary gate (FR1 of add-tracker-port, spec "Single Tracker
 * port speaking the factory's language" / scenario "Core compiles against the
 * port alone"): no class outside {@code adapter.tracker} may depend on a class
 * inside {@code adapter.tracker}. The take runner and every other core class
 * must go through the {@code Tracker} port (and its value model in
 * {@code app.port.tracker}) alone, never a tracker-specific adapter type or
 * concept (label, issue, transition id). Violations fail {@code ./gradlew check}
 * via this spec, naming the violating class.
 *
 * <p>The {@code adapter.tracker.inmemory} and {@code adapter.tracker.github}
 * packages (design D15) do not exist yet — tasks 2.x/4.x create them. Until
 * then this rule holds vacuously (there are no {@code adapter.tracker} classes
 * to depend on). It becomes meaningful the moment the first adapter class
 * lands: any core class that imports it will fail this check immediately.
 */
class TrackerPortBoundarySpec extends Specification {

    /**
     * Compiled production bytecode, imported once from the test runtime
     * classpath, mirroring {@link DomainPuritySpec}.
     */
    @Shared
    JavaClasses productionClasses = new ClassFileImporter().importPackages('com.github.oinsio.gnomish')

    def "FR1: no core class depends on adapter.tracker"() {
        given: 'the boundary rule forbidding core -> adapter.tracker dependencies'
        def rule = noClasses()
                .that().resideOutsideOfPackage('..adapter.tracker..')
                .should().dependOnClassesThat().resideInAPackage('..adapter.tracker..')

        expect: 'the rule holds over the production classes (check throws on violation)'
        rule.check(productionClasses)
    }
}
