package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.util.function.Predicate
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR2, FR10, NFR-R4 of migrate-verify-to-executor-contract (design D12 as amended 2026-11-12, D21;
 * the D9 rows of {@code PendingPoll} and {@code TaskState.withPendingPolls}): the open round's pending
 * entries have one writer. {@code PendingPoll} is the one caller of {@code
 * AttemptPersistence.persistPending} — from its first key's write ({@code answer}) and its key
 * rotation ({@code persisted}), the same write — besides the persistences that decorate the port and
 * hand the call on; so no check, executor or adapter writes a pending commit of its own, and a
 * rotated key is never kept only in memory. An entry is built only by that owner and by the state
 * file's parse ({@code StatePendingPollMapper}), and the list is set on a state only by the round's
 * view ({@code PendingRound}), the resumed state's carry ({@code Engine.resumedFrom}) and the parse
 * ({@code StateJsonMapper}). The targets are named by their fully qualified names and asserted
 * reached, so a rename fails here rather than leaving a rule vacuous.
 */
class PendingWriteOwnerSpec extends Specification {

    private static final String PORT = 'com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence'
    private static final String RECORD = 'com.github.oinsio.gnomish.domain.engine.PendingPollRecord'
    private static final String STATE = 'com.github.oinsio.gnomish.domain.engine.TaskState'
    private static final String POLL = 'com.github.oinsio.gnomish.domain.engine.PendingPoll'
    private static final String ROUND = 'com.github.oinsio.gnomish.domain.engine.PendingRound'
    private static final String ENGINE = 'com.github.oinsio.gnomish.domain.engine.Engine'
    private static final String RECORD_PARSE = 'com.github.oinsio.gnomish.adapter.git.state.StatePendingPollMapper'
    private static final String STATE_PARSE = 'com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper'

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    def "FR10, D12: the gate's targets exist and each owner makes the call reserved to it"() {
        expect:
        [
            PORT,
            RECORD,
            STATE,
            POLL,
            ROUND,
            ENGINE,
            RECORD_PARSE,
            STATE_PARSE
        ].every {
            productionClasses.contain(it)
        }

        and: 'the owner writes the first key and the rotated key through the one port operation'
        callers(POLL, calling(PORT, 'persistPending')) == ['answer', 'persisted'] as Set

        and:
        [POLL, RECORD_PARSE].every { owner ->
            productionClasses.get(owner).constructorCallsFromSelf.any {
                building(RECORD).test(it)
            }
        }
        [ROUND, ENGINE, STATE_PARSE].every { owner ->
            productionClasses.get(owner).methodCallsFromSelf.any {
                calling(STATE, 'withPendingPolls').test(it)
            }
        }
    }

    def "FR10, D12: only the owner, and the persistences that hand the call on, write a pending commit"() {
        expect:
        noClasses().that().doNotHaveFullyQualifiedName(POLL).and(notA(PORT))
                .should().callMethodWhere(calling(PORT, 'persistPending'))
                .check(productionClasses)
    }

    def "FR10, D12: a pending entry is built only by the owner and by the state file's parse"() {
        expect:
        noClasses().that().doNotHaveFullyQualifiedName(POLL).and().doNotHaveFullyQualifiedName(RECORD_PARSE)
                .should().callConstructorWhere(building(RECORD))
                .check(productionClasses)
    }

    def "D21: the round's list is set on a state only by the round's view, the resumed carry and the parse"() {
        expect:
        noClasses().that().doNotHaveFullyQualifiedName(ROUND).and().doNotHaveFullyQualifiedName(ENGINE)
                .and().doNotHaveFullyQualifiedName(STATE_PARSE)
                .should().callMethodWhere(calling(STATE, 'withPendingPolls'))
                .check(productionClasses)
    }

    private Set<String> callers(String owner, DescribedPredicate<JavaMethodCall> call) {
        productionClasses.get(owner).methodCallsFromSelf.findAll {
            call.test(it)
        }*.origin*.name as Set
    }

    private static DescribedPredicate<JavaClass> notA(String type) {
        DescribedPredicate.describe("not an implementation of ${type}".toString(), { JavaClass javaClass ->
            !javaClass.isAssignableTo(type)
        } as Predicate<JavaClass>)
    }

    private static DescribedPredicate<JavaMethodCall> calling(String owner, String name) {
        DescribedPredicate.describe("a call of ${name} on ${owner}".toString(), { JavaMethodCall call ->
            call.target.owner.isAssignableTo(owner) && call.target.name == name
        } as Predicate<JavaMethodCall>)
    }

    private static DescribedPredicate<JavaConstructorCall> building(String owner) {
        DescribedPredicate.describe("a construction of ${owner}".toString(), { JavaConstructorCall call ->
            call.target.owner.name == owner
        } as Predicate<JavaConstructorCall>)
    }
}
