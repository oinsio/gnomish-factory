package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.util.function.Predicate
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR3, FR11, NFR-R1 of migrate-verify-to-executor-contract (design D14 as amended 2026-11-13; the D9
 * row of {@code PendingRuns}): a key enters the run's table of pending runs of a program only from an
 * operation of a binding of that program in the run or from the pending record through the resume
 * path, never from the request on the wire. Two owners keep it so here: the check round is the one
 * caller of {@code PendingAdoption.adopt} — from its dispatch of a turn, whose {@code RECORDED} arm alone
 * adopts ({@code CheckRoundAdoptionSpec} pins the arm: an {@code ISSUED} turn adopts nothing), and
 * {@code PendingPoll} sets {@code RECORDED} on the resume path only — and a table is built only where
 * a run is built: beside the program's registry entry ({@code ExecutorRegistry}) and per case of the
 * conformance kit ({@code ProgramCandidate}). The table's readers and writers are confined to its
 * package by visibility, which the compiler enforces, so no rule repeats them; the one-table-per-binding
 * old way cannot come back, since {@code ProgramExecutor} takes its {@code PendingScope} and builds
 * none. The rules name their targets by their fully qualified names, so a check that the targets
 * still exist and still make the reserved calls is part of the gate: a renamed class fails here
 * rather than leaving a rule vacuous.
 */
class PendingAdoptionOwnerSpec extends Specification {

    private static final String ADOPTION = 'com.github.oinsio.gnomish.app.port.check.PendingAdoption'
    private static final String RUNS = 'com.github.oinsio.gnomish.adapter.agent.program.PendingRuns'
    private static final String CHECK_ROUND = 'com.github.oinsio.gnomish.app.CheckRound'
    private static final String REGISTRY = 'com.github.oinsio.gnomish.app.ExecutorRegistry'
    private static final String CANDIDATE = 'com.github.oinsio.gnomish.app.conformance.ProgramCandidate'
    private static final String PROGRAM = 'com.github.oinsio.gnomish.adapter.agent.program.ProgramExecutor'

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    def "FR3, D14: the gate's targets exist, so the rules below are not vacuous"() {
        expect:
        [
            ADOPTION,
            RUNS,
            CHECK_ROUND,
            REGISTRY,
            CANDIDATE,
            PROGRAM
        ].every {
            productionClasses.contain(it)
        }
        productionClasses.get(ADOPTION).methods*.name == ['adopt']

        and: 'each owner really makes the call the rules reserve to it'
        productionClasses.get(CHECK_ROUND).methodCallsFromSelf.any {
            calling(ADOPTION, ['adopt']).test(it)
        }
        [REGISTRY, CANDIDATE].every { owner ->
            productionClasses.get(owner).constructorCallsFromSelf.any {
                building(RUNS).test(it)
            }
        }

        and: 'the program executor holds a scope and builds no table of its own'
        !productionClasses.get(PROGRAM).constructorCallsFromSelf.any {
            building(RUNS).test(it)
        }
    }

    def "FR3, D14: only the check round adopts a recorded key, from its dispatch of a turn"() {
        expect:
        noClasses().that().doNotHaveFullyQualifiedName(CHECK_ROUND)
                .should().callMethodWhere(calling(ADOPTION, ['adopt']))
                .check(productionClasses)

        and:
        productionClasses.get(CHECK_ROUND).methodCallsFromSelf.findAll {
            calling(ADOPTION, ['adopt']).test(it)
        }*.origin*.name as Set == ['run'] as Set
    }

    def "FR3, D14: a table of pending runs is built only where a run is built: the registry entry and the kit's case"() {
        expect:
        noClasses().that().doNotHaveFullyQualifiedName(REGISTRY).and().doNotHaveFullyQualifiedName(CANDIDATE)
                .should().callConstructorWhere(building(RUNS))
                .check(productionClasses)
    }

    private static DescribedPredicate<JavaMethodCall> calling(String owner, List<String> names) {
        DescribedPredicate.describe("a call of ${names} on ${owner}".toString(), { JavaMethodCall call ->
            call.target.owner.isAssignableTo(owner) && call.target.name in names
        } as Predicate<JavaMethodCall>)
    }

    private static DescribedPredicate<JavaConstructorCall> building(String owner) {
        DescribedPredicate.describe("a construction of ${owner}".toString(), { JavaConstructorCall call ->
            call.target.owner.name == owner
        } as Predicate<JavaConstructorCall>)
    }
}
