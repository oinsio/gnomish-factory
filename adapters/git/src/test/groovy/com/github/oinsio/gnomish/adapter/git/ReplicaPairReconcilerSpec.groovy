package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
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
 * and demand manual git surgery — this change narrows to the claimless paths: the automatic discard
 * is gated on a tenure, so a diverged pair with no claim still stops and reports.
 */
class ReplicaPairReconcilerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def branchName = 'gnomish/PROJ-1'

    /** The take path: this instance holds a tenure on the task, so the discard is authorized. */
    def underTenure = { String taskId ->
        Optional.of(new ClaimEpoch(7L))
    } as ClaimEpochSource

    /** The manual-resume path: no tracker, no claim, so no tenure on anything. */
    def claimless = ClaimEpochSource.NONE

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
        def outcome = ReplicaPairReconciler.forWorktree(runner, clone, underTenure).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.NO_REMOTE_TRACKING_REF
    }

    def "local tip equal to origin tip reports EQUAL"() {
        given:
        def env = setUpClonedBranch()

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

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
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

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
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

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
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

        then: 'no exception demanding git surgery — the run continues from origin'
        noExceptionThrown()
        outcome == DivergenceOutcome.DIVERGED

        and: 'the local ref and its working tree now hold exactly what origin holds'
        tip(env.worktree) == originTip
        Files.exists(env.worktree.resolve('origin-only.txt'))
        !Files.exists(env.worktree.resolve('local-only.txt'))
    }

    // FR8: the automatic discard is justified by the claim protocol — origin advances only through
    // legitimate lease holders — so it is gated on that protocol being in force for this task. On a
    // claimless path (gnomish run --resume: no tracker, no claim) the premise does not hold and the
    // local line may be the operator's only copy, so the reconciler fails closed.
    def "FR8: a diverged pair with no tenure on the task refuses instead of discarding the local line"() {
        given: 'local and origin each gained an independent commit since their common ancestor'
        def env = setUpClonedBranch()
        commit(env.worktree, 'local-only.txt', 'local work')
        commit(env.seed, 'origin-only.txt', 'origin work')
        runner.run(env.seed, 'push', 'origin', branchName)
        def localTipBefore = tip(env.worktree)

        when:
        ReplicaPairReconciler.forWorktree(runner, env.worktree, claimless).reconcile('PROJ-1', branchName)

        then: 'the decision is handed back, naming both histories and the remedy'
        def ex = thrown(DivergedBranchException)
        ex.message.contains('PROJ-1')
        ex.message.contains(branchName)
        ex.message.contains(localTipBefore)
        ex.message.contains(tip(env.bare, "refs/heads/${branchName}"))
        ex.message.contains('holds no claim')

        and: 'nothing was destroyed: the local ref and the working tree still hold the local line'
        tip(env.worktree) == localTipBefore
        Files.exists(env.worktree.resolve('local-only.txt'))
        !Files.exists(env.worktree.resolve('origin-only.txt'))
    }

    def "FR8: container mode is gated the same way — no tenure, no ref-only discard"() {
        given: 'the clone holds the diverged branch as a ref only, with no host worktree'
        def env = setUpClonedBranch()
        commit(env.worktree, 'local-only.txt', 'local work')
        def localTipBefore = tip(env.worktree)
        runner.run(env.clone, 'worktree', 'remove', '--force', env.worktree.toString())
        commit(env.seed, 'origin-only.txt', 'origin work')
        runner.run(env.seed, 'push', 'origin', branchName)

        when:
        ReplicaPairReconciler.forClone(runner, env.clone, claimless).reconcile('PROJ-1', branchName)

        then:
        thrown(DivergedBranchException)
        tip(env.clone, "refs/heads/${branchName}") == localTipBefore
    }

    def "FR8: only the discard is gated — a claimless fast-forward still runs"() {
        given: 'local is strictly behind origin, which destroys nothing a claim could arbitrate'
        def env = setUpClonedBranch()
        commit(env.seed, 'b.txt', 'second')
        runner.run(env.seed, 'push', 'origin', branchName)

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, claimless).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.BEHIND
        Files.exists(env.worktree.resolve('b.txt'))
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
            ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)
        }

        then:
        def discard = events.find {
            it.formattedMessage.contains('discarding the local task branch')
        }
        discard != null
        discard.formattedMessage.contains(discardedTip)
        discard.formattedMessage.contains(tip(env.bare, "refs/heads/${branchName}"))
        events.every { !it.formattedMessage.contains('fast-forwarding') }

        and: 'the tenure it ran under is named, since that tenure is what authorized the discard'
        discard.formattedMessage.contains('epoch=7')
    }

    def "NFR-R3: the discard never touches origin — only the local ref moves"() {
        given:
        def env = setUpClonedBranch()
        commit(env.worktree, 'local-only.txt', 'local work')
        commit(env.seed, 'origin-only.txt', 'origin work')
        runner.run(env.seed, 'push', 'origin', branchName)
        def originTipBefore = tip(env.bare, "refs/heads/${branchName}")

        when:
        ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

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
        def outcome = ReplicaPairReconciler.forClone(runner, env.clone, underTenure).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.BEHIND
        tip(env.clone, "refs/heads/${branchName}") == originTip
    }

    def "FR8: a local tip that keeps moving loses the compare-and-swap and is never overwritten blindly"() {
        given: 'a git whose ref swap always loses, standing in for a second writer on the branch'
        def clone = initWorkingRepo(tempDir, 'clone-cas-loser')
        commit(clone, 'a.txt', 'first')

        when:
        ReplicaPairReconciler.forWorktree(new GitProcessRunner(alwaysLosingSwapGit().toString()), clone, underTenure)
                .reconcile('PROJ-1', branchName)

        then: 'the reconciler refuses to proceed rather than guess which line is real'
        def ex = thrown(IllegalStateException)
        ex.message.contains(branchName)
        ex.message.contains('second writer')

        and: "the diagnosis carries git's own account of the losing swap, not only the assertion"
        ex.message.contains('cannot lock ref')

        and: 'it spent exactly the bounded passes trying, not one more'
        Files.readAllLines(tempDir.resolve('swap-attempts.txt')).size() == 3
    }

    def "FR8: a losing swap that said nothing is named as such in the second-writer diagnosis"() {
        given: 'a losing-swap git whose update-ref fails silently'
        def clone = initWorkingRepo(tempDir, 'clone-cas-loser-silent')
        commit(clone, 'a.txt', 'first')

        when:
        ReplicaPairReconciler.forWorktree(
                new GitProcessRunner(silentlyLosingSwapGit().toString()), clone, underTenure)
                .reconcile('PROJ-1', branchName)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('second writer')
        ex.message.contains('(no stderr)')
    }

    /** {@link #alwaysLosingSwapGit} without the stderr line: a swap that loses without a word. */
    private Path silentlyLosingSwapGit() {
        def script = tempDir.resolve('silently-losing-swap-git.sh')
        script.toFile().text = """#!/bin/sh
for a in "\$@"; do
  case "\$a" in
    update-ref) exit 1;;
    merge-base) exit 1;;
    rev-parse) case "\$*" in *remotes*) echo ${'b' * 40};; *) echo ${'a' * 40};; esac; exit 0;;
    fetch) exit 0;;
  esac
done
exit 1
"""
        script.toFile().setExecutable(true)
        script
    }

    // FR8, NFR-R3: the ref swap and the working-tree resync are two durable steps of one repair. A
    // resync that failed silently left the discarded line sitting in the tree under the adopted
    // tip, where salvage reads it as the interrupted round's work and commits it straight back.
    def "FR8: a working-tree resync that fails refuses the reconciliation instead of reporting success"() {
        given: 'a git that swaps the diverged ref but cannot finish the resync'
        def clone = initWorkingRepo(tempDir, "clone-${failing}-fails")

        when:
        ReplicaPairReconciler.forWorktree(new GitProcessRunner(divergedGitFailing(failing).toString()), clone, underTenure)
                .reconcile('PROJ-1', branchName)

        then: 'the failure is named, with the branch, both tips and the remedy'
        def ex = thrown(IllegalStateException)
        ex.message.contains(branchName)
        ex.message.contains('a' * 40)
        ex.message.contains('b' * 40)
        ex.message.contains("git ${command}")
        ex.message.contains('could not lock')
        ex.message.contains('resume the task')

        where:
        failing | command
        'reset' | 'reset --hard'
        'clean' | 'clean -fd'
    }

    /**
     * A git reporting two diverged tips whose ref swap wins but whose {@code failing} working-tree
     * command fails: the shape of a resync that cannot complete behind a ref that already moved.
     */
    private Path divergedGitFailing(String failing) {
        def script = tempDir.resolve("diverged-git-failing-${failing}.sh")
        script.toFile().text = """#!/bin/sh
for a in "\$@"; do
  case "\$a" in
    ${failing}) echo 'fatal: could not lock the index' 1>&2; exit 1;;
    reset|clean|update-ref|fetch) exit 0;;
    merge-base) exit 1;;
    rev-parse) case "\$*" in *remotes*) echo ${'b' * 40};; *) echo ${'a' * 40};; esac; exit 0;;
  esac
done
exit 1
"""
        script.toFile().setExecutable(true)
        script
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
