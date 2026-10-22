package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.github.oinsio.gnomish.testsupport.ModuleBuildFile
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

    // FR1 of own-git-invocation-policy (design D1): the library takes its invocation policy from
    //     :gittransfer, which allows nothing itself, so the edge keeps it extraction-ready.
    // Scenario: build-system/module-layering / The git-object library depends only on subprocess and gittransfer
    def "FR1: gitobjects declares and allows only :subprocess and :gittransfer, the leaf's entry naming its edge"() {
        given: 'the build file, read as data and as text'
        def code = ModuleBuildFile.textOf(':gitobjects')
        List<String> lines = ModuleBuildFile.fileOf(':gitobjects').readLines()*.trim()

        expect: 'its internal production edges are exactly the two leafs'
        ModuleBuildFile.productionDependencies(code)
                .findResults {
                    (it =~ /project\('([^']+)'\)/).with {
                        it.find() ? it.group(1) : null
                    }
                }
                .toSet() == INTERNAL_EDGES

        and: 'its layering allowlist names the same two'
        ModuleBuildFile.allowedProjects(code) == INTERNAL_EDGES

        and: "the allowlist entry for :gittransfer carries a comment naming the edge it comes through"
        int entry = lines.indexOf("':gittransfer',")
        entry> 0
        commentAbove(lines, entry).contains('declared directly above')
    }

    private static final Set<String> INTERNAL_EDGES = [':subprocess', ':gittransfer'] as Set

    /** The contiguous comment lines directly above {@code index}, joined; empty when there are none. */
    private static String commentAbove(List<String> lines, int index) {
        int start = index
        while (start> 0 && lines[start - 1].startsWith('//')) {
            start--
        }
        lines.subList(start, index).join(' ')
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
