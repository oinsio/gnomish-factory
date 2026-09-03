package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * Accepted deviation 1 of {@code docs/adr/0004-logging-policy.md}, task 8.3 of
 * harden-logging-observability: exactly four {@code :domain} classes hold an SLF4J logger, for
 * port-failure paths where the framework-free alternative (an {@code EngineEvent.PortFailed}
 * variant carried out to a listener) has no consumer today.
 *
 * <p>The rule pins the list rather than the count, in both directions: a fifth domain logger fails
 * the gate, so it becomes a deliberate decision instead of drift; and a name disappearing from the
 * list fails it too, so the ADR's deviation cannot quietly outlive the code it describes.
 *
 * <p>Complements {@link DomainPuritySpec}, which forbids whole packages to {@code ..domain..};
 * this one narrows a dependency the domain is allowed to have, to the classes allowed to have it.
 */
class DomainLoggerAllowlistSpec extends Specification {

    /** The four names the ADR records; changing this list means changing the ADR. */
    private static final List<String> ALLOWED = [
        'AttemptJournal',
        'Events',
        'RoundExecution',
        'VerifyOrchestrator'
    ]

    /** Production bytecode only: specs legitimately log, and they share the package tree. */
    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    // FR1/ADR-0004 deviation 1: no domain class outside the allowlist reaches for a logger.
    def "only the four recorded domain classes hold a logger"() {
        given: 'the rule, exempting exactly the recorded four'
        def selected = ALLOWED.inject(noClasses().that().resideInAPackage('..domain..')) { conjunction, allowed ->
            conjunction.and().doNotHaveSimpleName(allowed)
        }
        def rule = selected
                .should().dependOnClassesThat().haveFullyQualifiedName('org.slf4j.LoggerFactory')
                .because('ADR 0004 deviation 1 pins domain logging to ' + ALLOWED.join(', '))

        expect: 'the rule holds over the production classes (check throws on violation)'
        rule.check(productionClasses)
    }

    // The allowlist is a record of what exists, not a permanent licence: an entry that stops
    //     logging must leave the list (and the ADR) rather than linger as a silent exemption.
    def "every allowlisted class really holds a logger"() {
        given:
        def domainClasses = productionClasses.findAll {
            it.packageName.contains('.domain')
        }

        expect: 'each allowlisted name resolves to a domain class that depends on LoggerFactory'
        ALLOWED.every { name ->
            domainClasses.any {
                it.simpleName == name &&
                it.directDependenciesFromSelf.any { d ->
                    d.targetClass.fullName == 'org.slf4j.LoggerFactory'
                }
            }
        }
    }
}
