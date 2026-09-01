package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of harden-logging-observability: a tip resolution recorded durably or gating a decision
 * refuses a blank result. Covers both attempt-persistence media (M6) — the sandboxed snapshot and
 * state commit, and the host worktree's round baseline — since a blank tip in either one outlives
 * the process it was written by.
 */
class VerifiedTipPersistenceSpec extends Specification implements BareGitRepoFixture, FailingSubcommandGitFixture {

    static final String TASK = 'PROJ-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    @TempDir
    Path tempDir

    Path cloneDir
    LocalBoxEnvironment box
    AttemptCommitRef attemptRef = new AttemptCommitRef()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize(BRANCH, null)
    }

    private GitProcessRunner blindToTips() {
        new GitProcessRunner(gitFailingOn(tempDir, 'rev-parse').toString())
    }

    def "FR13: a snapshot whose tip cannot be resolved records no attempt commit"() {
        given:
        def snapshotStep = new EnvironmentRoundSnapshot(box, blindToTips(), cloneDir, TASK, attemptRef)
        new File(box.workingCopy.toFile(), 'work.txt').text = 'gnome work'

        when:
        snapshotStep.snapshot(TASK, 'implement', 1)

        then: 'the step fails with the git evidence rather than recording the empty string'
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains(GIT_FAILURE_STDERR)

        and: 'no attempt commit was recorded at all'
        noAttemptCommitRecorded()
    }

    /** True when the round left {@link AttemptCommitRef} untouched — its own "not closed" signal. */
    private boolean noAttemptCommitRecorded() {
        try {
            attemptRef.required()
            return false
        } catch (IllegalStateException ignored) {
            return true
        }
    }

    def "FR13: the sandboxed persist refuses to start from a blank baseline tip"() {
        given:
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))

        when:
        new EnvironmentAttemptPersistence(
                box, blindToTips(), cloneDir, gitObjects, TASK, attemptRef, ClaimEpochSource.NONE)

        then:
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains(GIT_FAILURE_STDERR)
    }

    def "FR13: the host twin refuses a blank round baseline for the same reason"() {
        given:
        def worktree = initWorkingRepo(tempDir, 'worktree')
        new File(worktree.toFile(), 'a.txt').text = 'first'
        commitAll(worktree)
        assert gitExitCode(worktree, 'checkout', '-q', '-b', BRANCH) == 0

        when:
        new GitAttemptPersistence(blindToTips(), worktree, TASK, ClaimEpochSource.NONE)

        then:
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains(GIT_FAILURE_STDERR)
    }
}
