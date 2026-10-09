package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
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
    CurrentRound rounds = new CurrentRound()

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
        def snapshotStep = new EnvironmentRoundSnapshot(box, blindToTips(), cloneDir, TASK, OpenedRound.reopen(rounds, cloneDir, BRANCH))
        new File(box.workingCopy.toFile(), 'work.txt').text = 'gnome work'

        when:
        snapshotStep.snapshot(TASK, 'implement', 1)

        then: 'the step fails with the git evidence rather than recording the empty string'
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains(GIT_FAILURE_STDERR)

        and: 'no attempt commit was recorded at all'
        noAttemptCommitRecorded()
    }

    /** True when the open round was left without a snapshot — the cell's own "not closed" signal. */
    private boolean noAttemptCommitRecorded() {
        try {
            rounds.closed()
            return false
        } catch (IllegalStateException ignored) {
            return true
        }
    }

    // FR13 of make-checkpoint-gate-durable: the baseline is the round's token, so the one tip the
    //     sandboxed persist still resolves is the harvested state commit it judges
    def "FR13: the sandboxed persist refuses to judge a harvested tip it cannot resolve"() {
        given: 'a closed round, and a persistence whose git cannot resolve the tip after the harvest'
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp')))
        def closed = OpenedRound.reopen(rounds, cloneDir, BRANCH)
        new EnvironmentRoundSnapshot(box, new GitProcessRunner(), cloneDir, TASK, closed).snapshot(TASK, 'implement', 1)
        def persistence = new EnvironmentAttemptPersistence(
                box, blindToTips(), cloneDir, gitObjects, TASK, rounds, ClaimEpochSource.NONE)

        when:
        persistence.persist(TASK, TaskState.atStageStart('implement'),
                new ToolTrace(new AttemptKey(TASK, 'implement', 1), []))

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
