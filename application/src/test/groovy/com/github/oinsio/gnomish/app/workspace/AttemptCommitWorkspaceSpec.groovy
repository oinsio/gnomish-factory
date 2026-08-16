package com.github.oinsio.gnomish.app.workspace

import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import spock.lang.Specification

/**
 * FR21, FR26 (design D15) of add-sandbox-core: the sandboxed-mode {@code Workspace} check runners
 * downcast to. It carries the shared {@link AttemptCommitRef} rather than a frozen sha, so a
 * consumer holding one workspace for the whole run always reads the CURRENT round's attempt
 * commit.
 *
 * Added by task 8.7 of split-into-modules (design D13(c)).
 */
class AttemptCommitWorkspaceSpec extends Specification {

    // FR21: the workspace reports the commit its ref currently holds.
    def "reports the attempt commit the shared ref holds"() {
        given:
        def ref = new AttemptCommitRef()
        ref.record('sha-one')

        expect:
        new AttemptCommitWorkspace(ref).attemptCommitSha() == 'sha-one'
    }

    // D15, and the reason the workspace carries the REF and not a sha: one instance lives for the
    // whole run while each round re-records, so the same workspace must follow the ref's updates.
    def "follows the ref when a later round records a new commit"() {
        given:
        def ref = new AttemptCommitRef()
        def workspace = new AttemptCommitWorkspace(ref)
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
        new AttemptCommitWorkspace(new AttemptCommitRef()).attemptCommitSha()

        then:
        thrown(IllegalStateException)
    }
}
