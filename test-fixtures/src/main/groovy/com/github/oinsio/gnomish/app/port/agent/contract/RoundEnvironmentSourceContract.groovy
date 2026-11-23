package com.github.oinsio.gnomish.app.port.agent.contract

import com.github.oinsio.gnomish.app.describe.ProgramChannel
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * The port contract of {@link RoundEnvironmentSource} (FR18, M9 of extract-agent-round-runner,
 * design D14): the duties of D14's table that hold for every medium — the in-place host source,
 * its git-mode decoration and the box source — written once against the port and run by each
 * implementation's own module (testing.md, "Contract tests for every port"). What differs per
 * medium (the token's tip, the snapshot at the close, the mid-round listener) stays in each
 * implementation's own spec; the agreement the two round sources kept by hand is what this suite
 * holds.
 *
 * <p>A concrete subclass is named {@code <Implementation>ContractSpec} after the main-source class
 * it runs: the first feature asserts the subject really is that class, and {@code
 * RoundEnvironmentSourceContractCoverageSpec} in {@code :bootstrap} asserts every main-source
 * implementation has such a subclass.
 *
 * <p>Implements FR18, M9 of extract-agent-round-runner.
 */
abstract class RoundEnvironmentSourceContract extends Specification {

    /** The implementation under contract, built over the subclass's fixture; a fresh one per feature. */
    protected abstract RoundEnvironmentSource subject()

    /** A request for one round over the subclass's workspace. */
    protected abstract StageExecutor.Request roundRequest()

    /**
     * The token the next round the subject opens must carry, read from the medium before the open:
     * the tip the round opens on in a branch mode, empty for the in-place source.
     */
    protected abstract Optional<RoundToken> tokenAtOpen()

    /**
     * Whether the round's close did the medium's own closing work (D14, row "close"): the host
     * disposed the round's scratch area, the box recorded its snapshot. The scratch root is the one
     * the round named at its open: a disposed environment no longer answers for it.
     */
    protected abstract boolean closeEnded(RoundEnvironmentSource.Round round, String scratchRoot)

    /**
     * Whether the round runs in an environment an owner beyond the round holds — a box the run's
     * lease keeps for the stage, whose files die with the box (D14, row "discard": nothing). The
     * subclass shows it by the owner's own handle, never by assumption.
     */
    protected abstract boolean environmentOutlivesRound(RoundEnvironmentSource.Round round)

    // M9: the run is named after the implementation it pins, so the coverage gate's name match is real
    def "M9: the subject is the implementation this contract run is named after"() {
        expect:
        subject().getClass().simpleName + 'ContractSpec' == getClass().simpleName
    }

    // FR18, D14 row "open": every medium names the answer path to the agent and the round's identity
    def "FR18: an open round names its answer path to the agent and the identity it opened under"() {
        given:
        def source = subject()
        def expected = tokenAtOpen()

        when:
        def round = source.openRound(roundRequest())

        then:
        round.environment() != null
        round.environment().scratchRoot() != null
        round.answerFilePath() != null
        round.answerEnvFragment() == [(ProgramChannel.ANSWER_FILE_VARIABLE): round.answerFilePath().toString()]
        round.roundListener() != null
        source.identity().takeToken() == expected

        cleanup:
        round?.discard()
    }

    // FR18, D14 row "close": the close ends the round's environment duties, and a discard after it
    //     changes nothing the close left
    def "FR18: a close ends the round's environment duties and a discard after it is a no-op"() {
        given:
        def source = subject()
        def expected = tokenAtOpen()
        def round = source.openRound(roundRequest())
        def scratch = round.environment().scratchRoot()

        when:
        round.closeRound(RoundClose.VERIFIABLE)
        def afterClose = scratchPresent(scratch)

        then:
        closeEnded(round, scratch)

        when:
        round.discard()

        then:
        noExceptionThrown()
        scratchPresent(scratch) == afterClose
        closeEnded(round, scratch)
        source.identity().takeToken() == expected
    }

    // FR18, D14 row "discard": a discard after an open leaves no scratch area of the round's own
    def "FR18: a discard after an open leaves no scratch area of the round's own behind"() {
        given:
        def round = subject().openRound(roundRequest())
        def scratch = round.environment().scratchRoot()

        when:
        round.discard()

        then:
        environmentOutlivesRound(round) || !scratchPresent(scratch)
    }

    private static boolean scratchPresent(String scratchRoot) {
        Files.exists(Path.of(scratchRoot))
    }
}
