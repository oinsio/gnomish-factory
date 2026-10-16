package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of tighten-spec-contract (design D13, amended 2026-10-13, cross-instance resume finding):
 * where the clone's branch already holds origin's line — equal or ahead — the replica reconcile
 * brings the clone's register forward to origin's, so a clone that got the branch from
 * {@code git clone} reads the factory's record instead of classifying the branch {@code Unregistered}.
 * The register moves only forward, and only to a commit on the local line.
 */
class ReplicaPairRegisterFollowSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def branchName = 'gnomish/PROJ-1'
    static final String REGISTER = 'refs/gnomish-factory/PROJ-1'

    /** A bare origin plus a clone that has the task branch checked out as a worktree. */
    private Map<String, Path> setUpClonedBranch() {
        def bare = initBareRepo(tempDir, 'origin.git')
        def seed = initWorkingRepo(tempDir, 'seed')
        commit(seed, 'seed.txt', 'seed')
        gitOutput(seed, 'remote', 'add', 'origin', bare.toString())
        gitOutput(seed, 'push', 'origin', 'HEAD:refs/heads/main')
        gitOutput(seed, 'checkout', '-b', branchName)
        commit(seed, 'a.txt', 'first')
        gitOutput(seed, 'push', 'origin', branchName)

        def clone = seedClone(tempDir, bare.toString(), tempDir.resolve('clone'))
        fetchFromOrigin(clone, "refs/heads/${branchName}:refs/remotes/origin/${branchName}")
        def worktree = tempDir.resolve('worktree')
        assert runner.run(clone, 'worktree', 'add', worktree.toString(), branchName).exitCode() == 0
        [bare: bare, seed: seed, clone: clone, worktree: worktree]
    }

    private String tip(Path repo, String ref = 'HEAD') {
        gitOutput(repo, 'rev-parse', '--verify', ref)
    }

    private DivergenceOutcome reconcile(Path worktree) {
        ReplicaPairReconciler.forWorktree(runner, worktree, ClaimEpochSource.NONE).reconcile('PROJ-1', branchName)
    }

    // Scenario: git/git-task-persistence / The reconcile brings the register forward
    def "FR13: an equal branch with no register in the clone adopts origin's register"() {
        given: 'origin registered its tip; the clone has the same branch and no register, as a git clone leaves it'
        def env = setUpClonedBranch()
        gitOutput(env.seed, 'push', 'origin', "HEAD:${REGISTER}")
        def logs = LogCaptureSupport.attach(ReplicaPairReconciler, Level.DEBUG)

        when:
        def outcome = reconcile(env.worktree)

        then:
        outcome == DivergenceOutcome.EQUAL
        tip(env.clone, REGISTER) == tip(env.seed)
        tip(env.clone, "refs/heads/${branchName}") == tip(env.seed)

        and: 'the move landed on the first pass — no lost swap was reported'
        logs.list.every {
            !it.formattedMessage.startsWith(OperatorEvent.REPLICA_RESET_LOST_CAS.head())
        }

        cleanup:
        logs?.detach()
    }

    // Scenario: git/git-task-persistence / The reconcile brings the register forward
    def "FR13: a branch ahead of origin with an older register moves it forward to origin's"() {
        given: 'the clone registered the first commit; origin registered a later one; the clone then went ahead'
        def env = setUpClonedBranch()
        gitOutput(env.clone, 'update-ref', REGISTER, "refs/heads/${branchName}")
        commit(env.seed, 'b.txt', 'closing')
        gitOutput(env.seed, 'push', 'origin', branchName, "HEAD:${REGISTER}")
        fetchFromOrigin(env.clone, "refs/heads/${branchName}:refs/remotes/origin/${branchName}")
        gitOutput(env.worktree, 'merge', '--ff-only', "origin/${branchName}")
        commit(env.worktree, 'c.txt', 'gnome work above it')

        when:
        def outcome = reconcile(env.worktree)

        then:
        outcome == DivergenceOutcome.AHEAD
        tip(env.clone, REGISTER) == tip(env.seed)
    }

    // Scenario: git/git-task-persistence / A register ahead of origin's is never rolled back
    def "FR13: a lost closing push carried by a later branch-only push keeps the clone's register"() {
        given: 'origin registered the first commit'
        def env = setUpClonedBranch()
        gitOutput(env.seed, 'push', 'origin', "HEAD:${REGISTER}")

        and: 'the clone closed a commit whose atomic push was lost, then a branch-only push carried the line'
        commit(env.worktree, 'c.txt', 'closing commit, push lost')
        gitOutput(env.clone, 'update-ref', REGISTER, "refs/heads/${branchName}")
        def closing = tip(env.worktree)
        commit(env.worktree, 'd.txt', 'gnome work')
        assert runner.run(env.clone, 'push', 'origin', branchName).exitCode() == 0

        when:
        def outcome = reconcile(env.worktree)

        then: 'the branches are equal, origin\'s register is older, and the clone keeps its own'
        outcome == DivergenceOutcome.EQUAL
        tip(env.clone, 'refs/remotes/origin/gnomish-factory/PROJ-1') == tip(env.seed)
        tip(env.clone, REGISTER) == closing
    }

    def "FR13: a discarded local line takes its register with it when origin registers nothing"() {
        given: 'the clone registered a closing commit origin never received; origin moved on unregistered'
        def env = setUpClonedBranch()
        commit(env.worktree, 'c.txt', 'closing commit, push lost')
        gitOutput(env.clone, 'update-ref', REGISTER, "refs/heads/${branchName}")
        commit(env.seed, 'o.txt', 'origin line')
        assert runner.run(env.seed, 'push', 'origin', branchName).exitCode() == 0
        def underTenure = { String taskId ->
            Optional.of(new ClaimEpoch(7L))
        } as ClaimEpochSource

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

        then: 'the branch is origin\'s, and no register names a commit off it'
        outcome == DivergenceOutcome.DIVERGED
        tip(env.clone, "refs/heads/${branchName}") == tip(env.seed)
        runner.run(env.clone, 'rev-parse', '--verify', '--quiet', REGISTER).exitCode() != 0
    }

    def "FR13: a register on origin's line survives the discard when origin registers nothing"() {
        given: 'the clone registered a commit origin also holds; the local line then diverged above it'
        def env = setUpClonedBranch()
        gitOutput(env.clone, 'update-ref', REGISTER, "refs/heads/${branchName}")
        def registered = tip(env.clone, REGISTER)
        commit(env.worktree, 'c.txt', 'local work, push lost')
        commit(env.seed, 'o.txt', 'origin line')
        assert runner.run(env.seed, 'push', 'origin', branchName).exitCode() == 0
        def underTenure = { String taskId ->
            Optional.of(new ClaimEpoch(7L))
        } as ClaimEpochSource

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.DIVERGED
        tip(env.clone, REGISTER) == registered
    }

    def "FR13: origin's register off the local line is never adopted"() {
        given: 'origin registers a commit the task branch does not contain, forked from the seed commit below it'
        def env = setUpClonedBranch()
        gitOutput(env.seed, 'checkout', '-b', 'elsewhere', 'HEAD~1')
        commit(env.seed, 'x.txt', 'not on the task line')
        gitOutput(env.seed, 'push', 'origin', "HEAD:${REGISTER}")

        when:
        def outcome = reconcile(env.worktree)

        then:
        outcome == DivergenceOutcome.EQUAL
        runner.run(env.clone, 'rev-parse', '--verify', '--quiet', REGISTER).exitCode() != 0
    }

    def "FR13: a register already equal to origin's runs no ref transaction"() {
        given:
        def env = setUpClonedBranch()
        gitOutput(env.seed, 'push', 'origin', "HEAD:${REGISTER}")
        gitOutput(env.clone, 'update-ref', REGISTER, "refs/heads/${branchName}")
        def transactions = []
        GitRunner counting = new GitRunner() {
                    GitCommandResult run(Path cwd, String... args) {
                        runner.run(cwd, args)
                    }

                    GitCommandResult run(Path cwd, GitTransfer transfer) {
                        runner.run(cwd, transfer)
                    }

                    GitCommandResult run(Path cwd, RefTransaction transaction) {
                        transactions << transaction
                        runner.run(cwd, transaction)
                    }
                }

        when:
        def outcome = ReplicaPairReconciler.forWorktree(counting, env.worktree, ClaimEpochSource.NONE).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.EQUAL
        tip(env.clone, REGISTER) == tip(env.seed)
        transactions.isEmpty()
    }

    /**
     * A git over an equal pair whose clone holds no register while origin's names a commit on the
     * line, so the reconcile reaches the register move; {@code updateRef} answers that move.
     */
    private static ScriptedGit equalPairWithOriginRegister(GitCommandResult updateRef) {
        ScriptedGit.reftableClone()
                .answer(['fetch'], 0)
                .answer(['merge-base'], 0)
                .answer(['update-ref'], updateRef)
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    'refs/remotes/origin/gnomish-factory/*'
                ], 0, 'c' * 40 + '\n')
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    'refs/gnomish-factory/*'
                ], 1)
                .answer(['rev-parse'], 0, 'a' * 40 + '\n')
    }

    def "FR13: a register move that keeps losing its swap is classified again, then refused as a second writer"() {
        given:
        def git = equalPairWithOriginRegister(GitCommandResult.of(1, '', 'error: cannot lock ref\n'))
        def logs = LogCaptureSupport.attach(ReplicaPairReconciler, Level.DEBUG)

        when:
        ReplicaPairReconciler.forWorktree(git, tempDir, ClaimEpochSource.NONE).reconcile('PROJ-1', branchName)

        then: "the bounded passes ran, and the diagnosis carries git's own account"
        def ex = thrown(IllegalStateException)
        ex.message.contains('second writer')
        ex.message.contains('error: cannot lock ref')
        git.calls(['update-ref']).size() == 3

        and: 'the move was the register catch-up: the branch verified at its own tip, the register created'
        git.calls(['update-ref']).first().argv.contains("update refs/heads/${branchName} ${'a' * 40} ${'a' * 40}".toString())
        git.calls(['update-ref']).first().argv.contains("update ${REGISTER} ${'c' * 40} ${'0' * 40}".toString())

        and: 'every lost pass left one coded WARN'
        logs.list.count {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.REPLICA_RESET_LOST_CAS.head())
        } == 3

        cleanup:
        logs?.detach()
    }

    def "FR13: a register move that lost silently is named as such"() {
        given:
        def git = equalPairWithOriginRegister(GitCommandResult.of(1, '', ''))

        when:
        ReplicaPairReconciler.forWorktree(git, tempDir, ClaimEpochSource.NONE).reconcile('PROJ-1', branchName)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('(no stderr)')
    }

    def "FR13: a register move cut off before git answered refuses instead of counting as a lost swap"() {
        given:
        def git = equalPairWithOriginRegister(GitCommandResult.of(130, '', '', Termination.INTERRUPTED))

        when:
        ReplicaPairReconciler.forWorktree(git, tempDir, ClaimEpochSource.NONE).reconcile('PROJ-1', branchName)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('did not run to its own exit (INTERRUPTED)')
        git.calls(['update-ref']).size() == 1
    }
}
