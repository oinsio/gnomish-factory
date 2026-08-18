package com.github.oinsio.gnomish.app.workspace

import com.github.oinsio.gnomish.app.port.check.AttemptCommitWorkspace
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import spock.lang.Specification

/**
 * FR21, FR26 (design D15) of add-sandbox-core: the sandboxed-mode {@code Workspace} check runners
 * downcast to. It carries the shared {@link AttemptCommitRef} rather than a frozen sha, so a
 * consumer holding one workspace for the whole run always reads the CURRENT round's attempt
 * commit.
 *
 * FR1 of close-plugin-api-compilability-gap: the same reads go through the published
 * {@link AttemptCommitWorkspace} contract, which is all a third-party check ever sees of this
 * record.
 *
 * Added by task 8.7 of split-into-modules (design D13(c)).
 */
class RecordedAttemptCommitWorkspaceSpec extends Specification {

    // FR21: the workspace reports the commit its ref currently holds.
    def "reports the attempt commit the shared ref holds"() {
        given:
        def ref = new AttemptCommitRef()
        ref.record('sha-one')

        expect:
        new RecordedAttemptCommitWorkspace(ref).attemptCommitSha() == 'sha-one'
    }

    // D15, and the reason the workspace carries the REF and not a sha: one instance lives for the
    // whole run while each round re-records, so the same workspace must follow the ref's updates.
    def "follows the ref when a later round records a new commit"() {
        given:
        def ref = new AttemptCommitRef()
        def workspace = new RecordedAttemptCommitWorkspace(ref)
        ref.record('sha-one')

        when:
        ref.record('sha-two')

        then:
        workspace.attemptCommitSha() == 'sha-two'
    }

    // D15: verifying before the round was closed by a snapshot is a protocol violation, and the
    // workspace propagates the ref's refusal rather than substituting a placeholder.
    def "propagates the ref's refusal when no snapshot was recorded"() {
        when:
        new RecordedAttemptCommitWorkspace(new AttemptCommitRef()).attemptCommitSha()

        then:
        thrown(IllegalStateException)
    }

    // FR1 of close-plugin-api-compilability-gap: what a check client actually does — it receives
    // an opaque Workspace and narrows it to the API type, never to this record.
    def "a check narrowing the engine's workspace to the api type reads the round's sha"() {
        given:
        def ref = new AttemptCommitRef()
        ref.record('sha-one')
        Workspace handedToTheCheck = new RecordedAttemptCommitWorkspace(ref)

        when:
        AttemptCommitWorkspace narrowed = (AttemptCommitWorkspace) handedToTheCheck

        then:
        narrowed.attemptCommitSha() == 'sha-one'

        when: 'a later round records its own snapshot'
        ref.record('sha-two')

        then: 'the narrowed view follows it, like the record itself'
        narrowed.attemptCommitSha() == 'sha-two'
    }

    // FR1: the protocol error is part of the published contract, not an implementation detail the
    // narrowing hides.
    def "reading through the api type before any snapshot is a protocol error"() {
        given:
        AttemptCommitWorkspace narrowed = new RecordedAttemptCommitWorkspace(new AttemptCommitRef())

        when:
        narrowed.attemptCommitSha()

        then:
        thrown(IllegalStateException)
    }
}
