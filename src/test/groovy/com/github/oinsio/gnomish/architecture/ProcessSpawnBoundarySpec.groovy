package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * Process-spawn boundary gate (FR4 of add-sandbox-core: "forbid direct spawning by design"): the
 * gnome-product spawner packages — {@code adapter.agent} (agent-CLI rounds, judge votes) and
 * {@code adapter.check} (command checks) — must run every process through the {@code
 * TaskExecutionEnvironment} port, never {@link ProcessBuilder} directly. Only {@code
 * adapter.environment} (the host adapter, the sole spawn seam for gnome-product processes) and
 * {@code adapter.git} (factory-side git subprocesses, never gnome output) may touch {@code
 * ProcessBuilder}. A regression that reintroduces a direct spawn in a spawner package fails {@code
 * ./gradlew check} here, naming the offender.
 */
class ProcessSpawnBoundarySpec extends Specification {

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    def "FR4: gnome-product spawner packages do not use ProcessBuilder directly"() {
        given: 'the rule forbidding adapter.agent / adapter.check from depending on ProcessBuilder'
        def rule = noClasses()
                .that().resideInAnyPackage('..adapter.agent..', '..adapter.check..')
                .should().dependOnClassesThat().haveFullyQualifiedName('java.lang.ProcessBuilder')

        expect: 'the rule holds — those packages route every process through the environment port'
        rule.check(productionClasses)
    }
}
