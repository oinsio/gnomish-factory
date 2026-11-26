package com.github.oinsio.gnomish.architecture

import static com.github.oinsio.gnomish.architecture.OwnerCalls.building
import static com.github.oinsio.gnomish.architecture.OwnerCalls.calling
import static com.github.oinsio.gnomish.architecture.OwnerCalls.notA
import static com.github.oinsio.gnomish.architecture.OwnerCalls.outside
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.github.oinsio.gnomish.architecture.OwnerGateIntruders.Bracketing
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * Design D8 of extract-agent-round-runner, the row of the round's continuation; D11 and D14 as
 * amended 2026-11-25 (task 6.8; FR10, FR11, FR18): a round is opened once, by its {@code start}, and
 * a poll continues it. So a round's bracket has one opener and one closer:
 *
 * <ul>
 *   <li>{@code RoundEnvironmentSource.openRound} is called only on the start path of {@code
 *       ProgramStageExecutor} (its {@code execute}; its {@code poll} takes {@code continueRound}) and
 *       by the delegating {@code MidRoundPushRounds}, which opens its host's round from its own
 *       {@code openRound} and, for a restored pending round, from its {@code continueRound} — the host
 *       in-place source mints nothing;
 *   <li>the token mint, {@code RoundTokenMint}, is built and called only by the two minting sources,
 *       {@code MidRoundPushRounds} and {@code SandboxRoundEnvironmentSource}, from their {@code
 *       openRound} alone — never from a {@code continueRound}, so a poll mints nothing ({@code
 *       CurrentRoundWriterSpec} pins the cell's {@code open} to the mint);
 *   <li>{@code Round.closeRound} is called only by {@code ProgramStageExecutor}, on a terminal result,
 *       and by a round that hands its close on to the round it decorates ({@code MidRoundPushRounds}'
 *       push round).
 * </ul>
 *
 * <p>Calls are judged by type in the bytecode. The targets are named by their fully qualified names
 * and asserted reached, and a detector feature shows each rule red on a class that opens, mints or
 * closes outside its owner.
 *
 * <p>FR10, FR11, FR18 of extract-agent-round-runner.
 */
class RoundBracketOwnerSpec extends Specification {

    private static final String SOURCE = 'com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource'
    private static final String ROUND = 'com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource$Round'
    private static final String STAGE = 'com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor'
    private static final String PUSH_ROUNDS = 'com.github.oinsio.gnomish.adapter.git.MidRoundPushRounds'
    private static final String PUSH_ROUND = 'com.github.oinsio.gnomish.adapter.git.MidRoundPushRounds$PushRound'
    private static final String BOX_ROUNDS = 'com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource'
    private static final String MINT = 'com.github.oinsio.gnomish.adapter.git.RoundTokenMint'

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    def "D8: the gate's targets exist and each owner makes the call reserved to it"() {
        expect:
        [
            SOURCE,
            ROUND,
            STAGE,
            PUSH_ROUNDS,
            PUSH_ROUND,
            BOX_ROUNDS,
            MINT
        ].every {
            productionClasses.contain(it)
        }

        and: 'the stage wrapper opens on its start path only, and closes from the round it runs'
        callers(STAGE, calling(SOURCE, 'openRound')) == ['execute'] as Set
        !callers(STAGE, calling(SOURCE, 'continueRound')).isEmpty()
        !callers(STAGE, calling(ROUND, 'closeRound')).isEmpty()

        and: 'the delegating source opens its host round, fresh or restored'
        callers(PUSH_ROUNDS, calling(SOURCE, 'openRound')) == ['openRound', 'continueRound'] as Set
        callers(PUSH_ROUND, calling(ROUND, 'closeRound')) == ['closeRound'] as Set

        and: 'each minting source builds the mint and mints from its open alone'
        [PUSH_ROUNDS, BOX_ROUNDS].every { owner ->
            productionClasses.get(owner).constructorCallsFromSelf.any {
                building(MINT).test(it)
            } &&
            callers(owner, calling(MINT, 'open')) == ['openRound'] as Set
        }
    }

    def "D8, D14: openRound is called only on the stage wrapper's start path and in the delegating source"() {
        expect:
        openRule().check(productionClasses)

        and: 'no other round source delegates an open'
        implementationsCalling(SOURCE, calling(SOURCE, 'openRound')) == [PUSH_ROUNDS] as Set
    }

    def "D8, D11: the round token is minted only by the two minting sources"() {
        expect:
        mintRule().check(productionClasses)
        buildRule().check(productionClasses)
    }

    def "D8, D14: closeRound is called only by the stage wrapper and by a round that hands its close on"() {
        expect:
        closeRule().check(productionClasses)

        and:
        implementationsCalling(ROUND, calling(ROUND, 'closeRound')) == [PUSH_ROUND] as Set
    }

    def "D8: each rule goes red on a class that brackets a round outside its owner"() {
        given:
        def intruders = new ClassFileImporter().importClasses(Bracketing)

        when:
        rule.check(intruders)

        then:
        def failure = thrown(AssertionError)
        failure.message.contains(Bracketing.simpleName)

        where:
        name | rule
        'the open rule' | openRule()
        'the close rule' | closeRule()
    }

    def "D8, D11: the mint rules go red on a mint outside the allowed sources"() {
        when: 'one of the two minting sources is taken off the allowlist'
        rule.check(productionClasses)

        then: 'its real mint is reported'
        def failure = thrown(AssertionError)
        failure.message.contains(BOX_ROUNDS)

        where:
        name | rule
        'the mint rule' | mintRule([PUSH_ROUNDS])
        'the build rule' | buildRule([PUSH_ROUNDS])
    }

    private static def openRule() {
        noClasses().that().doNotHaveFullyQualifiedName(STAGE).and(notA(SOURCE))
                .should().callMethodWhere(calling(SOURCE, 'openRound'))
    }

    private static def mintRule(List<String> minters = [PUSH_ROUNDS, BOX_ROUNDS]) {
        noClasses().that(outside(minters)).should().callMethodWhere(calling(MINT, 'open'))
    }

    private static def buildRule(List<String> minters = [PUSH_ROUNDS, BOX_ROUNDS]) {
        noClasses().that(outside(minters)).should().callConstructorWhere(building(MINT))
    }


    private static def closeRule() {
        noClasses().that().doNotHaveFullyQualifiedName(STAGE).and(notA(ROUND))
                .should().callMethodWhere(calling(ROUND, 'closeRound'))
    }

    private Set<String> callers(String owner, DescribedPredicate<JavaMethodCall> call) {
        OwnerCalls.callers(productionClasses, owner, call)
    }

    private Set<String> implementationsCalling(String port, DescribedPredicate<JavaMethodCall> call) {
        OwnerCalls.implementationsCalling(productionClasses, port, call)
    }
}
