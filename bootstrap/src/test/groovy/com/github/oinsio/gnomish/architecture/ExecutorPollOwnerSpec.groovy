package com.github.oinsio.gnomish.architecture

import static com.github.oinsio.gnomish.architecture.OwnerCalls.calling
import static com.github.oinsio.gnomish.architecture.OwnerCalls.notA
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.github.oinsio.gnomish.architecture.OwnerGateIntruders.ContractPolling
import com.github.oinsio.gnomish.architecture.OwnerGateIntruders.StagePolling
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location
import spock.lang.Shared
import spock.lang.Specification

/**
 * Design D8 of extract-agent-round-runner, the rows of {@code PendingPoll} and of the round's
 * continuation; D11 as amended 2026-11-25 (task 6.8; FR10, FR11): a pending round is polled by one
 * owner. In the engine and the application layer the one poller is {@code PendingPoll}, and what it
 * polls is the stage port: {@code StageExecutor.poll}, the operation a poll continues the open round
 * through (task 6.4b). The contract's {@code Executor.poll} is never called there — the engine reaches
 * an executor only through the stage port, and the choice between a {@code start} and a {@code poll}
 * of the contract is made below it by the one dispatch of a turn, {@code PendingTurnRun} (D11). So:
 *
 * <ul>
 *   <li>a call of {@code poll} on a {@code StageExecutor} or on a contract {@code Executor}, from a
 *       class of {@code :domain} or {@code :application}, is made only by {@code PendingPoll};
 *   <li>across the whole tree, {@code StageExecutor.poll} is called only by {@code PendingPoll} and by
 *       the port's implementations that hand the call on from their own {@code poll} — the D8 list
 *       {@code CheckRoundRoute}, {@code ExecutorDispatch}, {@code ResumeVerificationStageExecutor},
 *       {@code SnapshottingStageExecutor};
 *   <li>across the whole tree, the contract's {@code Executor.poll} is called only by {@code
 *       PendingTurnRun}, by the conformance kit's pending cases ({@code PendingCases}: the kit is the
 *       contract's judge and drives the interface itself) and by the contract's decorators from their
 *       own {@code poll} ({@code GuardedExecutor}, {@code PinCheckedExecutor}).
 * </ul>
 *
 * <p>The receivers are judged by type in the bytecode, not by the spelling {@code .poll(}: the feed's
 * {@code FeedCycle.poll} and a workflow poll of {@code :adapters:scm} are other operations. The targets
 * are named by their fully qualified names and asserted reached, and the scope of the layer rule is
 * asserted to contain the owner and a known non-owner of each module, so a moved class or an empty
 * scope fails here rather than leaving a rule vacuous. A detector feature shows each rule red on a
 * class that polls outside the owner.
 *
 * <p>FR10, FR11 of extract-agent-round-runner.
 */
class ExecutorPollOwnerSpec extends Specification {

    private static final String STAGE_PORT = 'com.github.oinsio.gnomish.domain.engine.port.StageExecutor'
    private static final String CONTRACT = 'com.github.oinsio.gnomish.executorcontract.Executor'
    private static final String OWNER = 'com.github.oinsio.gnomish.domain.engine.PendingPoll'
    private static final String TURN_RUN = 'com.github.oinsio.gnomish.adapter.agent.program.PendingTurnRun'
    private static final String KIT = 'com.github.oinsio.gnomish.app.conformance.PendingCases'

    /** The stage port's implementations that hand a poll on (D8, the round's continuation row). */
    private static final List<String> STAGE_FORWARDERS = [
        'com.github.oinsio.gnomish.app.CheckRoundRoute',
        'com.github.oinsio.gnomish.app.ExecutorDispatch',
        'com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor',
        'com.github.oinsio.gnomish.adapter.git.SnapshottingStageExecutor'
    ]

    /** The contract's decorators, which hand a poll on to the executor they wrap. */
    private static final List<String> CONTRACT_FORWARDERS = [
        'com.github.oinsio.gnomish.adapter.agent.program.GuardedExecutor',
        'com.github.oinsio.gnomish.adapter.check.PinCheckedExecutor'
    ]

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    /** The classes of {@code :domain} and {@code :application} main: the layer the owner rule scopes. */
    @Shared
    JavaClasses layerClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .withImportOption({ Location location ->
        inLayer(location)
    } as ImportOption)
    .importPackages('com.github.oinsio.gnomish')

    def "D8: the gate's targets exist, the layer scope holds the owner and its modules, and each owner polls"() {
        expect:
        ([
            STAGE_PORT,
            CONTRACT,
            OWNER,
            TURN_RUN,
            KIT
        ] + STAGE_FORWARDERS + CONTRACT_FORWARDERS).every {
            productionClasses.contain(it)
        }

        and: 'the layer scope is neither empty nor wider than the two modules'
        layerClasses.contain(OWNER)
        layerClasses.contain('com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource')
        !layerClasses.contain(TURN_RUN)
        !layerClasses.contain('com.github.oinsio.gnomish.app.ExecutorDispatch')

        and: 'the owner polls the stage port from its one wait path, and the turn dispatch polls the contract'
        callers(OWNER, polling(STAGE_PORT)) == ['waitFor'] as Set
        callers(TURN_RUN, polling(CONTRACT)) == ['run'] as Set
        !callers(KIT, polling(CONTRACT)).isEmpty()

        and: 'every listed forwarder hands the poll on from its own poll'
        STAGE_FORWARDERS.every {
            callers(it, polling(STAGE_PORT)) == ['poll'] as Set
        }
        CONTRACT_FORWARDERS.every {
            callers(it, polling(CONTRACT)) == ['poll'] as Set
        }
    }

    def "D8: in the domain and application layer only PendingPoll polls an executor"() {
        expect:
        layerRule().check(layerClasses)
    }

    def "D8, D11: StageExecutor.poll is called only by PendingPoll and the listed forwarders, from their poll"() {
        expect:
        stageRule().check(productionClasses)

        and: 'no implementation outside the list hands a poll on'
        implementationsCalling(STAGE_PORT) == STAGE_FORWARDERS as Set
    }

    def "D11: the contract's Executor.poll is called only by the turn dispatch, the kit and the listed decorators"() {
        expect:
        contractRule().check(productionClasses)

        and:
        implementationsCalling(CONTRACT) == CONTRACT_FORWARDERS as Set
    }

    def "D8: each rule goes red on a class that polls outside the owner"() {
        given:
        def intruders = new ClassFileImporter().importClasses(StagePolling, ContractPolling)

        when:
        rule.check(intruders)

        then:
        def failure = thrown(AssertionError)
        failure.message.contains(intruder.simpleName)

        where:
        name | rule | intruder
        'the layer rule' | layerRule() | StagePolling
        'the stage rule' | stageRule() | StagePolling
        'the contract rule' | contractRule() | ContractPolling
    }

    private static def layerRule() {
        noClasses().that().doNotHaveFullyQualifiedName(OWNER)
                .should().callMethodWhere(polling(STAGE_PORT)).orShould().callMethodWhere(polling(CONTRACT))
    }

    private static def stageRule() {
        noClasses().that().doNotHaveFullyQualifiedName(OWNER).and(notA(STAGE_PORT))
                .should().callMethodWhere(polling(STAGE_PORT))
    }

    private static def contractRule() {
        noClasses().that().doNotHaveFullyQualifiedName(TURN_RUN).and().doNotHaveFullyQualifiedName(KIT)
                .and(notA(CONTRACT))
                .should().callMethodWhere(polling(CONTRACT))
    }

    private Set<String> callers(String owner, DescribedPredicate<JavaMethodCall> call) {
        OwnerCalls.callers(productionClasses, owner, call)
    }

    private Set<String> implementationsCalling(String port) {
        OwnerCalls.implementationsCalling(productionClasses, port, polling(port))
    }

    private static boolean inLayer(Location location) {
        [
            '/domain/build/',
            '/application/build/'
        ].any { location.contains(it) }
    }

    /** The port's own {@code poll}, by its parameter types: an implementation's private overload is not it. */
    private static DescribedPredicate<JavaMethodCall> polling(String port) {
        calling(port, 'poll', port == STAGE_PORT ? [
            'com.github.oinsio.gnomish.domain.engine.port.StageExecutor$Request',
            'com.github.oinsio.gnomish.domain.engine.port.PendingTurn'
        ] : [
            'com.github.oinsio.gnomish.executorcontract.ExecutorRequest',
            'com.github.oinsio.gnomish.pendingkey.PendingKey'
        ])
    }
}
