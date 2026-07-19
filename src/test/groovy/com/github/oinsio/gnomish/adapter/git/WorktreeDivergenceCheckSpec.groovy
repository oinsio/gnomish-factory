package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, NFR-R3 of add-git-workflow: local/origin divergence on resume — equal, behind
 * (fast-forward + discard leftovers), ahead (continue from local), diverged (clear error, no
 * mutation). No remote-tracking ref at all (no remote, or never fetched) is trivially equal.
 */
class WorktreeDivergenceCheckSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def branchName = 'gnomish/PROJ-1'

    private void commit(Path repo, String fileName, String content) {
        new File(repo.toFile(), fileName).text = content
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', fileName)
    }

    /** A bare origin plus a clone that has the task branch checked out as a worktree. */
    private Map<String, Path> setUpClonedBranch() {
        def bare = initBareRepo(tempDir, 'origin.git')
        def seed = initWorkingRepo(tempDir, 'seed')
        commit(seed, 'seed.txt', 'seed')
        runner.run(seed, 'remote', 'add', 'origin', bare.toString())
        runner.run(seed, 'push', 'origin', 'HEAD:refs/heads/main')
        runner.run(seed, 'checkout', '-b', branchName)
        commit(seed, 'a.txt', 'first')
        runner.run(seed, 'push', 'origin', branchName)

        def clone = tempDir.resolve('clone')
        def cloneResult = runner.run(tempDir, 'clone', bare.toString(), clone.toString())
        assert cloneResult.exitCode() == 0: "clone failed: ${cloneResult.stderr()}"
        runner.run(clone, 'fetch', 'origin', "${branchName}:refs/remotes/origin/${branchName}")

        def worktree = tempDir.resolve('worktree')
        def add = runner.run(clone, 'worktree', 'add', worktree.toString(), branchName)
        assert add.exitCode() == 0: "worktree add failed: ${add.stderr()}"

        [bare: bare, seed: seed, clone: clone, worktree: worktree]
    }

    def "no remote-tracking ref (no origin configured) reports NO_REMOTE_TRACKING_REF and mutates nothing"() {
        given:
        def clone = initWorkingRepo(tempDir, 'lone-clone')
        commit(clone, 'a.txt', 'first')
        runner.run(clone, 'checkout', '-b', branchName)
        def check = new WorktreeDivergenceCheck(runner, clone)

        when:
        def outcome = check.reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.NO_REMOTE_TRACKING_REF
    }

    def "local tip equal to origin tip reports EQUAL"() {
        given:
        def env = setUpClonedBranch()
        def check = new WorktreeDivergenceCheck(runner, env.worktree)

        when:
        def outcome = check.reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.EQUAL
    }

    def "local behind origin fast-forwards the worktree and discards uncommitted leftovers"() {
        given: 'a worktree at the old tip, plus another instance pushing a new commit to origin'
        def env = setUpClonedBranch()
        commit(env.seed, 'b.txt', 'second')
        runner.run(env.seed, 'push', 'origin', branchName)
        runner.run(env.clone, 'fetch', 'origin', "${branchName}:refs/remotes/origin/${branchName}")

        and: 'uncommitted leftovers sitting in the worktree from the outdated history line'
        Files.writeString(env.worktree.resolve('leftover.txt'), 'stale work')

        def check = new WorktreeDivergenceCheck(runner, env.worktree)

        when:
        def outcome = check.reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.BEHIND

        and: 'the worktree fast-forwarded to origin\'s tip'
        Files.exists(env.worktree.resolve('b.txt'))

        and: 'the uncommitted leftover was discarded'
        !Files.exists(env.worktree.resolve('leftover.txt'))
    }

    def "local ahead of origin (unpushed commits) reports AHEAD and leaves the worktree untouched"() {
        given: 'the worktree has a local commit that never reached origin'
        def env = setUpClonedBranch()
        commit(env.worktree, 'c.txt', 'unpushed')
        def localTipBefore = runner.run(env.worktree, 'rev-parse', 'HEAD').stdout().trim()

        def check = new WorktreeDivergenceCheck(runner, env.worktree)

        when:
        def outcome = check.reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.AHEAD

        and: 'the worktree was not mutated'
        runner.run(env.worktree, 'rev-parse', 'HEAD').stdout().trim() == localTipBefore
        Files.exists(env.worktree.resolve('c.txt'))
    }

    def "diverged histories throw DivergedBranchException naming the taskId and branch, without mutating the worktree"() {
        given: 'local and origin each gained an independent commit since their common ancestor'
        def env = setUpClonedBranch()
        commit(env.worktree, 'local-only.txt', 'local work')
        def localTipBefore = runner.run(env.worktree, 'rev-parse', 'HEAD').stdout().trim()

        commit(env.seed, 'origin-only.txt', 'origin work')
        runner.run(env.seed, 'push', 'origin', branchName)
        runner.run(env.clone, 'fetch', 'origin', "${branchName}:refs/remotes/origin/${branchName}")

        def check = new WorktreeDivergenceCheck(runner, env.worktree)

        when:
        check.reconcile('PROJ-1', branchName)

        then:
        def ex = thrown(DivergedBranchException)
        ex.message.contains('PROJ-1')
        ex.message.contains(branchName)

        and: 'the worktree was not mutated'
        runner.run(env.worktree, 'rev-parse', 'HEAD').stdout().trim() == localTipBefore
        Files.exists(env.worktree.resolve('local-only.txt'))
    }
}
