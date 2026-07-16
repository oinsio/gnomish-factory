package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import spock.lang.Shared
import spock.lang.Specification

/**
 * Domain-purity gate (FR10 of load-pipeline-config, design D7): the pure
 * {@code domain} package must never depend on the {@code adapter} package,
 * the filesystem ({@code java.nio.file}), or Jackson. Violations fail
 * {@code ./gradlew check} via this spec, naming the violating class.
 */
class DomainPuritySpec extends Specification {

    /**
     * Compiled production bytecode, imported once from the test runtime
     * classpath. Groovy test classes under the same root package are also
     * picked up, but the rules only constrain {@code ..domain..}.
     */
    @Shared
    JavaClasses productionClasses = new ClassFileImporter().importPackages('com.github.oinsio.gnomish')

    def "FR10: no domain class depends on #forbiddenPackage"() {
        given: 'the purity rule forbidding the dependency'
        def rule = noClasses()
                .that().resideInAPackage('..domain..')
                .should().dependOnClassesThat().resideInAPackage(forbiddenPackage)

        expect: 'the rule holds over the production classes (check throws on violation)'
        rule.check(productionClasses)

        where:
        forbiddenPackage << [
            '..adapter..',
            'java.nio.file..',
            'com.fasterxml.jackson..'
        ]
    }

    /**
     * Regression guard for add-stage-engine task 7.1 / design D9: the engine landed its
     * model and ports in {@code domain.engine} and {@code domain.engine.port}, and relies
     * on this pre-existing {@code ..domain..} rule to police their purity — no rule change.
     * This asserts the two new packages actually contribute production classes AND that the
     * rule's own {@code ..domain..} selector matches every one of them, so the coverage this
     * change assumed cannot silently lapse (e.g. a package rename moving the engine outside
     * {@code ..domain..} would fail here rather than quietly escape the purity gate).
     */
    def "NFR-O1/D9: the ..domain.. selector covers every class in the new engine package #enginePackage"() {
        given: 'the exact engine package this change added and the rule\'s own domain selector'
        def domainSelector = resideInAPackage('..domain..')
        def packageClasses = productionClasses.findAll { it.packageName == enginePackage }

        expect: 'the package contributes production classes for the rule to constrain'
        !packageClasses.isEmpty()

        and: 'the ..domain.. selector matches every one of them — the purity gate covers the package'
        packageClasses.every { domainSelector.test(it) }

        where:
        enginePackage << [
            'com.github.oinsio.gnomish.domain.engine',
            'com.github.oinsio.gnomish.domain.engine.port'
        ]
    }
}
