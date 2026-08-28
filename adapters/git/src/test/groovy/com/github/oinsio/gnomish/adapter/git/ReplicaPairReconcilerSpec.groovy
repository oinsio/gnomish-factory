package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, NFR-R3 of harden-task-branch-contract (design D8): one reconciler for the clone-versus-origin
 * replica pair in both execution modes — equal and ahead keep, behind fast-forwards, and true
 * divergence discards the local line automatically under the claim. Both moves are a
 * compare-and-swap of the local ref; origin history is never touched.
 *
 * Supersedes {@code WorktreeDivergenceCheckSpec} of add-git-workflow, whose FR9 rule — stop the run
 * and demand manual git surgery — this change removes.
 */
class ReplicaPairReconcilerSpec extends Specification implements BareGitRepoFixture {

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

    private String tip(Path repo, String ref = 'HEAD') {
        runner.run(repo, 'rev-parse', ref).stdout().trim()
    }

    def "no remote-tracking ref (no origin configured) reports NO_REMOTE_TRACKING_REF and mutates nothing"() {
        given:
        def clone = initWorkingRepo(tempDir, 'lone-clone')
        commit(clone, 'a.txt', 'first')
        runner.run(clone, 'checkout', '-b', branchName)

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, clone).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.NO_REMOTE_TRACKING_REF
    }

    def "local tip equal to origin tip reports EQUAL"() {
        given:
        def env = setUpClonedBranch()

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.EQUAL
    }

    def "local behind origin fast-forwards the worktree and discards uncommitted leftovers"() {
        given: 'a worktree at the old tip, plus another instance pushing a new commit to origin'
        def env = setUpClonedBranch()
        commit(env.seed, 'b.txt', 'second')
        runner.run(env.seed, 'push', 'origin', branchName)

        and: 'uncommitted leftovers sitting in the worktree from the outdated history line'
        Files.writeString(env.worktree.resolve('leftover.txt'), 'stale work')

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.BEHIND

        and: "the worktree fast-forwarded to origin's tip"
        Files.exists(env.worktree.resolve('b.txt'))

        and: 'the uncommitted leftover was discarded'
        !Files.exists(env.worktree.resolve('leftover.txt'))
    }

    def "local ahead of origin (unpushed commits) reports AHEAD and leaves the worktree untouched"() {
        given: 'the worktree has a local commit that never reached origin'
        def env = setUpClonedBranch()
        commit(env.worktree, 'c.txt', 'unpushed')
        def localTipBefore = tip(env.worktree)

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.AHEAD

        and: 'the worktree was not mutated'
        tip(env.worktree) == localTipBefore
        Files.exists(env.worktree.resolve('c.txt'))
    }

    def "FR8: diverged histories discard the local line automatically and continue from origin"() {
        given: 'local and origin each gained an independent commit since their common ancestor'
        def env = setUpClonedBranch()
        commit(env.worktree, 'local-only.txt', 'local work')
        commit(env.seed, 'origin-only.txt', 'origin work')
        runner.run(env.seed, 'push', 'origin', branchName)
        def originTip = tip(env.bare, "refs/heads/${branchName}")

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree).reconcile('PROJ-1', branchName)

        then: 'no exception demanding git surgery — the run continues from origin'
        noExceptionThrown()
        outcome == DivergenceOutcome.DIVERGED

        and: 'the local ref and its working tree now hold exactly what origin holds'
        tip(env.worktree) == originTip
        Files.exists(env.worktree.resolve('origin-only.txt'))
        !Files.exists(env.worktree.resolve('local-only.txt'))
    }

    // NFR-O1: an automatic discard is the one repair that destroys something, so it is named with
    // both tips. A fast-forward, which destroys nothing, must not borrow that wording.
    def "NFR-O1: the discard is logged as a discard, with both tips, and a fast-forward is not"() {
        given:
        def env = setUpClonedBranch()
        commit(env.worktree, 'local-only.txt', 'local work')
        commit(env.seed, 'origin-only.txt', 'origin work')
        runner.run(env.seed, 'push', 'origin', branchName)
        def discardedTip = tip(env.worktree)

        when:
        def events = capture {
            ReplicaPairReconciler.forWorktree(runner, env.worktree).reconcile('PROJ-1', branchName)
        }

        then:
        def discard = events.find {
            it.formattedMessage.contains('discarding the local task branch')
        }
        discard != null
        discard.formattedMessage.contains(discardedTip)
        discard.formattedMessage.contains(tip(env.bare, "refs/heads/${branchName}"))
        events.every { !it.formattedMessage.contains('fast-forwarding') }
    }

    def "NFR-R3: the discard never touches origin — only the local ref moves"() {
        given:
        def env = setUpClonedBranch()
        commit(env.worktree, 'local-only.txt', 'local work')
        commit(env.seed, 'origin-only.txt', 'origin work')
        runner.run(env.seed, 'push', 'origin', branchName)
        def originTipBefore = tip(env.bare, "refs/heads/${branchName}")

        when:
        ReplicaPairReconciler.forWorktree(runner, env.worktree).reconcile('PROJ-1', branchName)

        then:
        tip(env.bare, "refs/heads/${branchName}") == originTipBefore
    }

    def "FR8: container mode reconciles refs alone, with no working tree to resync"() {
        given: 'the clone holds the branch as a ref only — the boxed task has no host worktree'
        def env = setUpClonedBranch()
        runner.run(env.clone, 'worktree', 'remove', '--force', env.worktree.toString())
        commit(env.seed, 'b.txt', 'second')
        runner.run(env.seed, 'push', 'origin', branchName)
        def originTip = tip(env.bare, "refs/heads/${branchName}")

        when:
        def outcome = ReplicaPairReconciler.forClone(runner, env.clone).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.BEHIND
        tip(env.clone, "refs/heads/${branchName}") == originTip
    }

    def "FR8: a local tip that keeps moving loses the compare-and-swap and is never overwritten blindly"() {
        given: 'a git whose ref swap always loses, standing in for a second writer on the branch'
        def clone = initWorkingRepo(tempDir, 'clone-cas-loser')
        commit(clone, 'a.txt', 'first')

        when:
        ReplicaPairReconciler.forWorktree(new GitProcessRunner(alwaysLosingSwapGit().toString()), clone)
                .reconcile('PROJ-1', branchName)

        then: 'the reconciler refuses to proceed rather than guess which line is real'
        def ex = thrown(IllegalStateException)
        ex.message.contains(branchName)
        ex.message.contains('second writer')

        and: 'it spent exactly the bounded passes trying, not one more'
        Files.readAllLines(tempDir.resolve('swap-attempts.txt')).size() == 3
    }

    /** Runs {@code emit} with a {@link ListAppender} attached to the reconciler's own logger. */
    private static List<ILoggingEvent> capture(Closure<?> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(ReplicaPairReconciler)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
        }
        appender.list
    }

    /**
     * A git reporting two unrelated tips whose {@code update-ref} always fails: the shape of a
     * losing compare-and-swap, which a real repository under a held lease will not produce.
     */
    private Path alwaysLosingSwapGit() {
        def script = tempDir.resolve('losing-swap-git.sh')
        script.toFile().text = """#!/bin/sh
for a in "\$@"; do
  case "\$a" in
    update-ref) echo x >> '${tempDir.resolve('swap-attempts.txt')}'; echo 'error: cannot lock ref' 1>&2; exit 1;;
    merge-base) exit 1;;
    rev-parse) case "\$*" in *remotes*) echo bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb;; *) echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;; esac; exit 0;;
    fetch) exit 0;;
  esac
done
exit 1
"""
        script.toFile().setExecutable(true)
        script
    }
}
