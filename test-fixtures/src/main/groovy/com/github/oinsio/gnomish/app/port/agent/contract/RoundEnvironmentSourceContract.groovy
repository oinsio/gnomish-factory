package com.github.oinsio.gnomish.app.port.agent.contract

import com.github.oinsio.gnomish.app.describe.ProgramChannel
import com.github.oinsio.gnomish.app.port.agent.NoOpenRoundException
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.TerminalClose
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
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
 * <p>As amended 2026-11-25 (D11, D14), the source holds its open round: a second open while one is
 * open is refused, a poll with no open round is refused, and a poll's round names the identity, the
 * environment and the answer path the round opened with.
 *
 * <p>Implements FR18, M9, FR10 of extract-agent-round-runner.
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

    /**
     * Moves the tip a round of the subject would open on — a commit on the worktree or the task
     * branch — so a token minted after it differs from the one the round opened with; nothing for the
     * in-place source, which mints none.
     */
    protected abstract void moveTip()

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
        round.closeRound(TerminalClose.VERIFIABLE)
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

    // FR10, D14 as amended 2026-11-25: the source holds its open round; a second open is refused
    //     before anything is minted
    def "FR10: a second open while a round is open is refused, and nothing is minted for it"() {
        given:
        def source = subject()
        def expected = tokenAtOpen()
        def round = source.openRound(roundRequest())
        moveTip()

        when:
        source.openRound(roundRequest())

        then:
        thrown(IllegalStateException)
        source.identity().takeToken() == expected

        cleanup:
        round?.discard()
    }

    // FR10, D14 as amended 2026-11-25: a poll never opens a round, so with none open it is refused
    def "FR10: a poll with no open round is refused, and no round is opened for it"() {
        given:
        def source = subject()

        when:
        source.continueRound(roundRequest())

        then:
        thrown(NoOpenRoundException)
        tokenOrNone(source.identity()) == Optional.empty()
    }

    // FR10, D14 as amended 2026-11-25: the round goes pending and a poll is handed that very round
    def "FR10: a poll's round names the identity, the environment and the answer path the round opened with"() {
        given:
        def source = subject()
        def expected = tokenAtOpen()
        def round = source.openRound(roundRequest())
        round.goPending()
        moveTip()

        when:
        def polled = source.continueRound(roundRequest())

        then:
        polled.environment().is(round.environment())
        polled.answerFilePath() == round.answerFilePath()
        polled.answerEnvFragment() == round.answerEnvFragment()
        polled.roundListener().is(round.roundListener())
        source.identity().takeToken() == expected

        cleanup:
        polled?.discard()
    }

    // FR10, D14: a closed round is let go, so a later poll has no round to continue
    def "FR10: after the close a poll is refused and a new round may open"() {
        given:
        def source = subject()
        def round = source.openRound(roundRequest())
        round.closeRound(TerminalClose.VERIFIABLE)

        when:
        source.continueRound(roundRequest())

        then:
        thrown(NoOpenRoundException)

        when:
        def next = source.openRound(roundRequest())

        then:
        next != null

        cleanup:
        next?.discard()
    }

    /** The identity's token, or empty when no round was ever opened in it. */
    private static Optional<RoundToken> tokenOrNone(RoundIdentity identity) {
        try {
            identity.takeToken()
        } catch (IllegalStateException ignored) {
            Optional.empty()
        }
    }

    private static boolean scratchPresent(String scratchRoot) {
        Files.exists(Path.of(scratchRoot))
    }
}
