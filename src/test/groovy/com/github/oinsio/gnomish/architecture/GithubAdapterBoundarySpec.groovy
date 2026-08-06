package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * GitHub adapter boundary gate (design D4 of add-external-check-github-actions): {@code
 * adapter.tracker.github} and {@code adapter.check.github} are siblings that both depend on the
 * shared plumbing in {@code adapter.github} (HTTP client, retry config, conditional-request
 * cache) but must never depend on each other's internals — each is a distinct port
 * implementation (Tracker vs. ExternalCheckClient) and the module-boundary rule
 * (.claude/rules/process-invariants.md) forbids sibling-internal imports. Until now this held by
 * convention only; this spec makes it a compiled gate. Violations fail {@code ./gradlew check}
 * via this spec, naming the violating class.
 */
class GithubAdapterBoundarySpec extends Specification {

    /**
     * Compiled production bytecode, imported once from the test runtime classpath, mirroring
     * {@link TrackerPortBoundarySpec} — test fixtures are excluded so this rule constrains
     * production wiring only.
     */
    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    def "D4: adapter.check.github does not depend on adapter.tracker.github"() {
        given: 'the boundary rule forbidding the check adapter from reaching into the tracker adapter'
        def rule = noClasses()
                .that().resideInAPackage('..adapter.check.github..')
                .should().dependOnClassesThat().resideInAPackage('..adapter.tracker.github..')

        expect: 'the rule holds over the production classes (check throws on violation)'
        rule.check(productionClasses)
    }

    def "D4: adapter.tracker.github does not depend on adapter.check.github"() {
        given: 'the boundary rule forbidding the tracker adapter from reaching into the check adapter'
        def rule = noClasses()
                .that().resideInAPackage('..adapter.tracker.github..')
                .should().dependOnClassesThat().resideInAPackage('..adapter.check.github..')

        expect: 'the rule holds over the production classes (check throws on violation)'
        rule.check(productionClasses)
    }
}
