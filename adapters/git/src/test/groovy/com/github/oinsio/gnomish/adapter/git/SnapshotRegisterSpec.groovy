package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.RoundToken
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of tighten-spec-contract (design D13): the snapshot subject is a parsing contract at the
 * task's registered commit and nowhere else. The snapshot is a closing commit the factory registers
 * locally; a commit above the register — a gnome's — is never read as one.
 */
class SnapshotRegisterSpec extends Specification implements BareGitRepoFixture, RegisteredBranchFixture {

    static final String TASK = 'TIP-1'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path clone

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        new File(clone.toFile(), 'a.txt').text = 'seed'
        commitAll(clone)
        gitOutput(clone, 'checkout', '-b', 'gnomish/TIP-1')
    }

    /** A factory closing commit with {@code subject}, registered as the closing push registers it. */
    private void tipWithSubject(String subject) {
        gnomeCommit(subject)
        registerTip(clone, TASK)
    }

    /** A commit the gnome makes: the branch moves, the register does not. */
    private void gnomeCommit(String subject) {
        new File(clone.toFile(), 'a.txt').text = subject
        commitAll(clone, subject)
    }

    private String tip() {
        gitOutput(clone, 'rev-parse', 'HEAD')
    }

    // FR13 of tighten-spec-contract (design D13): the snapshot subject is a parsing contract at the
    //     registered commit and nowhere else — the gnome can commit a snapshot-shaped subject above
    //     the register, and it is not a pending verification.
    // Scenario: git/git-task-persistence / The snapshot subject is read at the register only
    def "FR13: a snapshot-shaped subject above the register is not a snapshot"() {
        given: 'the factory registered an ordinary round commit'
        tipWithSubject('gnomish: round implement#1')

        when: 'the gnome commits a well-formed snapshot subject above it'
        gnomeCommit(ServiceCommitMessages.snapshot('implement', 1, RoundToken.of(tip())))

        then:
        new SnapshotTipCheck(runner, clone).inspect(TASK).isEmpty()
    }

    // FR13 of tighten-spec-contract: a registered snapshot under a gnome commit above it is still
    //     the pending verification, read at the register — the commit above changes nothing.
    def "FR13: a registered snapshot under a commit above it is still pending, at the snapshot"() {
        given:
        tipWithSubject(ServiceCommitMessages.snapshot('implement', 2, RoundToken.of(tip())))
        def snapshot = tip()
        gnomeCommit('gnome work after the snapshot')

        expect:
        new SnapshotTipCheck(runner, clone).inspect(TASK).get().attemptCommit() == snapshot
    }
}
