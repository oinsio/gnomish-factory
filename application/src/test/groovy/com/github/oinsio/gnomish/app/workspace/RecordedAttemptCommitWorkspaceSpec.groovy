package com.github.oinsio.gnomish.app.workspace

import com.github.oinsio.gnomish.app.port.check.AttemptCommitWorkspace
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.app.workspace.fake.ClosedRounds
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import spock.lang.Specification

/**
 * FR21, FR26 (design D15) of add-sandbox-core: the sandboxed-mode {@code Workspace} check runners
 * downcast to. It carries the run's {@link CurrentRound} rather than a frozen sha, so a
 * consumer holding one workspace for the whole run always reads the CURRENT round's attempt
 * commit — the closed round's (FR13 of make-checkpoint-gate-durable, design D10).
 *
 * FR1 of close-plugin-api-compilability-gap: the same reads go through the published
 * {@link AttemptCommitWorkspace} contract, which is all a third-party check ever sees of this
 * record.
 *
 * Added by task 8.7 of split-into-modules (design D13(c)).
 */
class RecordedAttemptCommitWorkspaceSpec extends Specification {

    // FR21: the workspace reports the attempt commit of the cell's closed round.
    def "reports the attempt commit of the closed round"() {
        given:
        def ref = ClosedRounds.at('sha-one')

        expect:
        new RecordedAttemptCommitWorkspace(ref).attemptCommitSha() == 'sha-one'
    }

    // D15, and the reason the workspace carries the CELL and not a sha: one instance lives for the
    // whole run while each round opens and closes in it, so the same workspace must follow it.
    def "follows the cell when a later round is closed by a new commit"() {
        given:
        def ref = ClosedRounds.at('sha-one')
        def workspace = new RecordedAttemptCommitWorkspace(ref)

        when:
        nextRound(ref, 'sha-two')

        then:
        workspace.attemptCommitSha() == 'sha-two'
    }

    // D15: verifying before the round was closed by a snapshot is a protocol violation, and the
    // workspace propagates the cell's refusal rather than substituting a placeholder — nor the
    // previous round's commit once a new round has opened (design D10 of make-checkpoint-gate-durable).
    def "propagates the cell's refusal when the current round has no snapshot: #situation"() {
        when:
        new RecordedAttemptCommitWorkspace(rounds).attemptCommitSha()

        then:
        thrown(IllegalStateException)

        where:
        situation | rounds
        'no round opened' | new CurrentRound()
        'a new round opened after a closed one' | reopened(ClosedRounds.at('sha-one'))
    }

    private static CurrentRound reopened(CurrentRound rounds) {
        rounds.open(RoundToken.of('ffff'))
        rounds
    }

    private static void nextRound(CurrentRound rounds, String sha) {
        reopened(rounds).snapshotted(sha)
    }

    // FR1 of close-plugin-api-compilability-gap: what a check client actually does — it receives
    // an opaque Workspace and narrows it to the API type, never to this record.
    def "a check narrowing the engine's workspace to the api type reads the round's sha"() {
        given:
        def ref = ClosedRounds.at('sha-one')
        Workspace handedToTheCheck = new RecordedAttemptCommitWorkspace(ref)

        when:
        AttemptCommitWorkspace narrowed = (AttemptCommitWorkspace) handedToTheCheck

        then:
        narrowed.attemptCommitSha() == 'sha-one'

        when: 'a later round is closed by its own snapshot'
        nextRound(ref, 'sha-two')

        then: 'the narrowed view follows it, like the record itself'
        narrowed.attemptCommitSha() == 'sha-two'
    }

    // FR1: the protocol error is part of the published contract, not an implementation detail the
    // narrowing hides.
    def "reading through the api type before any snapshot is a protocol error"() {
        given:
        AttemptCommitWorkspace narrowed = new RecordedAttemptCommitWorkspace(new CurrentRound())

        when:
        narrowed.attemptCommitSha()

        then:
        thrown(IllegalStateException)
    }
}
