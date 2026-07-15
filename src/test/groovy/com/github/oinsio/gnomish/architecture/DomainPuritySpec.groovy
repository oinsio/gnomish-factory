package com.github.oinsio.gnomish.architecture

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
}
