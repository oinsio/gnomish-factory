package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
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
    static final String REGISTER = 'refs/gnomish-factory/PROJ-1'

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
        FixtureGitConfig.addRemote(seed, 'origin', bare.toString())
        runner.run(seed, 'push', 'origin', 'HEAD:refs/heads/main')
        runner.run(seed, 'checkout', '-b', branchName)
        commit(seed, 'a.txt', 'first')
        runner.run(seed, 'push', 'origin', branchName)

        def clone = seedClone(tempDir, bare.toString(), tempDir.resolve('clone'))
        fetchFromOrigin(clone, "refs/heads/${branchName}:refs/remotes/origin/${branchName}")

        def worktree = tempDir.resolve('worktree')
        def add = runner.run(clone, 'worktree', 'add', worktree.toString(), branchName)
        assert add.exitCode() == 0: "worktree add failed: ${add.stderr()}"

        [bare: bare, seed: seed, clone: clone, worktree: worktree]
    }

    private String tip(Path repo, String ref = 'HEAD') {
        runner.run(repo, 'rev-parse', ref).stdout().forParsing().trim()
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

    // ADR 0008: the reconcile fetch is a factory transfer like any other, built by the owner
    //     (GitTransfer) — so it cannot auto-follow a tag into the operator's refs/tags or truncate
    //     the clone's single FETCH_HEAD file while doing its own narrow job.
    def "the reconcile fetch writes neither an auto-followed tag nor FETCH_HEAD"() {
        given: 'origin carries a tag on the branch this reconcile will fetch'
        def env = setUpClonedBranch()
        commit(env.seed, 'b.txt', 'second')
        runner.run(env.seed, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'tag', 'v9.9')
        runner.run(env.seed, 'push', 'origin', branchName)
        runner.run(env.seed, 'push', 'origin', 'v9.9')
        assert gitOutput(env.clone, 'for-each-ref', '--format=%(refname)', 'refs/tags').isEmpty()
        // The fixture's own clone and seeding fetch wrote one; removing it leaves the reconcile
        // fetch as the only thing that could put it back.
        Files.deleteIfExists(env.clone.resolve('.git/FETCH_HEAD'))

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

        then: 'the branch was reconciled'
        outcome == DivergenceOutcome.BEHIND

        and: "and nothing else in the operator's clone moved"
        gitOutput(env.clone, 'for-each-ref', '--format=%(refname)', 'refs/tags').isEmpty()
        !Files.exists(env.clone.resolve('.git/FETCH_HEAD'))
    }

    // FR13 of tighten-spec-contract: the reconcile fetch carries origin's register beside the
    //     branch; adopting origin's line adopts its register, so the next closing push does not
    //     carry a register origin's branch has left behind.
    def "FR13: adopting origin's line adopts origin's register, which the fetch brought with the branch"() {
        given: 'origin moved branch and register together; the clone registered the older tip'
        def env = setUpClonedBranch()
        runner.run(env.clone, 'update-ref', REGISTER, "refs/heads/${branchName}")
        commit(env.seed, 'b.txt', 'second')
        runner.run(env.seed, 'push', 'origin', branchName, "HEAD:${REGISTER}")

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.BEHIND
        tip(env.clone, REGISTER) == tip(env.seed)
        tip(env.clone, 'refs/remotes/origin/gnomish-factory/PROJ-1') == tip(env.seed)
    }

    def "FR13: a local line ahead of origin keeps the clone's register — a fetch never rolls it back"() {
        given: 'origin registered the first commit; the clone closed one more it has not pushed yet'
        def env = setUpClonedBranch()
        runner.run(env.seed, 'push', 'origin', "HEAD:${REGISTER}")
        commit(env.worktree, 'c.txt', 'local closing commit')
        runner.run(env.clone, 'update-ref', REGISTER, "refs/heads/${branchName}")

        when:
        def outcome = ReplicaPairReconciler.forWorktree(runner, env.worktree, underTenure).reconcile('PROJ-1', branchName)

        then:
        outcome == DivergenceOutcome.AHEAD
        tip(env.clone, REGISTER) == tip(env.worktree)
        tip(env.clone, 'refs/remotes/origin/gnomish-factory/PROJ-1') == tip(env.seed)
    }

    // Scenario: lifecycle/task-branch-contract / Local-ahead is kept, local-behind fast-forwards
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

    // Scenario: lifecycle/task-branch-contract / Local-ahead is kept, local-behind fast-forwards
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

    // Scenario: git/git-task-persistence / Diverged histories resolve automatically under the claim
    // Scenario: tracker/tracker-take / Stale local branch is discarded and work continues
    // Scenario: lifecycle/task-branch-contract / Divergence resolves automatically under the lease
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
    // Scenario: git/git-task-persistence / A claimless resume refuses the discard
    // Scenario: lifecycle/task-branch-contract / A diverged pair with no live claim is not discarded
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

    // Scenario: git/git-task-persistence / A claimless resume refuses the discard
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
        // FR15 of harden-logging-observability: the discard destroys local work under the claim, so
        // its identity is the catalog code, not the sentence.
        def discard = events.find {
            it.formattedMessage.startsWith(OperatorEvent.REPLICA_LOCAL_BRANCH_DISCARDED.head())
        }
        discard != null
        discard.level == Level.WARN
        discard.formattedMessage.contains(discardedTip)
        discard.formattedMessage.contains(tip(env.bare, "refs/heads/${branchName}"))
        events.every { !it.formattedMessage.contains('fast-forwarding') }

        and: 'the tenure it ran under is named, since that tenure is what authorized the discard'
        discard.formattedMessage.contains('epoch=7')
    }

    // Scenario: git/git-task-persistence / The CAS reset is the only non-fast-forward write
    // Scenario: git/git-task-persistence / The reset never touches origin
    // Scenario: lifecycle/task-branch-contract / Origin history is never rewritten
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

    /** What git says when the ref swap loses to another writer. */
    private static final String CANNOT_LOCK_REF = 'error: cannot lock ref'

    /** What git says when the working-tree resync cannot take the index. */
    private static final String LOCK_INDEX = 'fatal: could not lock the index'

    /**
     * A git over two unrelated tips — the local one all {@code a}, origin's tracking ref all
     * {@code b}, no common ancestor — whose fetch succeeds; the rows after it decide the swap.
     */
    private static ScriptedGit diverged(ScriptedGit git = ScriptedGit.reftableClone()) {
        git.answer(['fetch'], 0)
        .answer(['merge-base'], 1)
        .answer([
            'rev-parse',
            '--verify',
            '--quiet',
            'refs/remotes/*'
        ], 0, 'b' * 40 + '\n')
        .answer(['rev-parse'], 0, 'a' * 40 + '\n')
    }

    // Scenario: git/git-task-persistence / A moved tip fails the CAS reset
    // Scenario: tracker/tracker-take / The discard is a compare-and-swap on the local ref
    // Scenario: lifecycle/task-branch-contract / Divergence resolves automatically under the lease
    def "FR8: a local tip that keeps moving loses the compare-and-swap and is never overwritten blindly"() {
        given: 'a git whose ref swap always loses, standing in for a second writer on the branch'
        def losingSwapGit = diverged(ScriptedGit.reftableClone().answer(['update-ref'], 1, '', CANNOT_LOCK_REF + '\n'))
        def clone = tempDir

        and: 'a sink for the lines it emits on its way to refusing'
        List<ILoggingEvent> events = []

        when:
        capture(events) {
            ReplicaPairReconciler.forWorktree(losingSwapGit, clone, underTenure)
            .reconcile('PROJ-1', branchName)
        }

        then: 'the reconciler refuses to proceed rather than guess which line is real'
        def ex = thrown(IllegalStateException)
        ex.message.contains(branchName)
        ex.message.contains('second writer')

        and: "the diagnosis carries git's own account of the losing swap, not only the assertion"
        ex.message.contains(CANNOT_LOCK_REF)

        and: 'it spent exactly the bounded passes trying, not one more'
        losingSwapGit.calls(['update-ref']).size() == 3

        and: 'FR14, FR15: every lost pass leaves one coded WARN naming the task and the pass number'
        def lost = events.findAll {
            it.formattedMessage.startsWith(OperatorEvent.REPLICA_RESET_LOST_CAS.head())
        }
        lost*.level == [Level.WARN] * 3
        lost*.formattedMessage.every {
            it.contains('taskId=PROJ-1') && it.contains("branch=${branchName}")
        }

        and: 'the passes are counted from one, so the third line is the last the bound allows'
        lost.collect {
            def matcher = it.formattedMessage =~ /pass=(\d+)/
            matcher.find()
            matcher.group(1)
        } == ['1', '2', '3']
    }

    def "FR8: a losing swap that said nothing is named as such in the second-writer diagnosis"() {
        given: 'a losing-swap git whose update-ref fails silently'
        def git = diverged(ScriptedGit.reftableClone().answer(['update-ref'], 1))

        when:
        ReplicaPairReconciler.forWorktree(git, tempDir, underTenure)
                .reconcile('PROJ-1', branchName)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('second writer')
        ex.message.contains('(no stderr)')
    }

    // FR8, NFR-R3: the ref swap and the working-tree resync are two durable steps of one repair. A
    // resync that failed silently left the discarded line sitting in the tree under the adopted
    // tip, where salvage reads it as the interrupted round's work and commits it straight back.
    def "FR8: a working-tree resync that fails refuses the reconciliation instead of reporting success"() {
        given: 'a git that swaps the diverged ref but cannot finish the resync'
        def git = diverged(ScriptedGit.reftableClone()
                .answer([failing], 1, '', LOCK_INDEX + '\n')
                .answer(['reset'], 0)
                .answer(['clean'], 0)
                .answer(['update-ref'], 0))

        when:
        ReplicaPairReconciler.forWorktree(git, tempDir, underTenure)
                .reconcile('PROJ-1', branchName)

        then: 'the failure is named, with the branch, both tips and the remedy'
        def ex = thrown(IllegalStateException)
        ex.message.contains(branchName)
        ex.message.contains('a' * 40)
        ex.message.contains('b' * 40)
        ex.message.contains("git ${command}")
        ex.message.contains(LOCK_INDEX)
        ex.message.contains('resume the task')

        where:
        failing | command
        'reset' | 'reset --hard'
        'clean' | 'clean -fd'
    }

    /** Runs {@code emit} with a {@link ch.qos.logback.core.read.ListAppender} attached to the reconciler's own logger. */
    private static List<ILoggingEvent> capture(Closure<?> emit) {
        List<ILoggingEvent> sink = []
        capture(sink, emit)
        sink
    }

    /**
     * The same capture for a body that <em>throws</em>: the events land in {@code sink} on the way
     * out, so a feature can assert both the exception and the lines emitted before it. Returning
     * them would not do — an assignment never completes when its right-hand side throws.
     */
    private static void capture(List<ILoggingEvent> sink, Closure<?> emit) {
        // Through the shared helper rather than a hand-rolled ListAppender (`.claude/rules/logging.md`):
        // it restores the logger's level on the way out, and the Logback level registry is JVM-global.
        // Pinned at DEBUG because these features assert on the absence of an INFO line too.
        def logs = LogCaptureSupport.attach(ReplicaPairReconciler, Level.DEBUG)
        try {
            emit()
        } finally {
            sink.addAll(logs.list)
            logs.detach()
        }
    }
}
