package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * Boundary gate for the extraction-ready {@code gitobjects} library (design D19, FR25 of
 * add-sandbox-core), pinned in both directions:
 * <ol>
 *   <li>the library depends only on the JDK and SLF4J — never on factory packages, Spring, or
 *       Jackson — so it can be lifted into its own module by a folder move;</li>
 *   <li>the pure {@code domain} never reaches for the library — only the adapter layer wires it
 *       (the {@code factory → lib only} edge of design D19).</li>
 * </ol>
 * Violations fail {@code ./gradlew check}, naming the offending class.
 */
class GitObjectsBoundarySpec extends Specification {

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    def "FR25: gitobjects does not depend on #forbiddenPackage"() {
        given:
        def rule = noClasses()
                .that().resideInAPackage('..gitobjects..')
                .should().dependOnClassesThat().resideInAPackage(forbiddenPackage)

        expect:
        rule.check(productionClasses)

        where:
        forbiddenPackage << [
            'com.github.oinsio.gnomish',
            '..adapter..',
            '..app..',
            '..domain..',
            '..status..',
            '..usage..',
            'org.springframework..',
            'com.fasterxml.jackson..'
        ]
    }

    def "FR25: the pure domain never depends on the gitobjects library"() {
        given:
        def rule = noClasses()
                .that().resideInAPackage('..domain..')
                .should().dependOnClassesThat().resideInAPackage('..gitobjects..')

        expect:
        rule.check(productionClasses)
    }
}
