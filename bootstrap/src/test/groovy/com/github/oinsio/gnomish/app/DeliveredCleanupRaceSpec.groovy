package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.DeliveredCleanupMedium.CONTAINER
import static com.github.oinsio.gnomish.app.DeliveredCleanupMedium.HOST

import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Two instances finishing the cleanup of one finished task's branch at the same time (FR4, NFR-R1
 * of fix-terminal-receipt-convergence; design D2: "a ref update that loses a race logs DEBUG and
 * returns"). Each instance is its own process in production, so each {@code take <ref>} here runs
 * over its own {@code TaskGit} bundle, on its own thread, the two meeting at every tracker read;
 * both work in the one registered clone, as two commands of one factory home do.
 *
 * <p>Whichever way the two interleave — one loses the compare-and-swap of the ref update, or the
 * later one already reads {@code Delivered} — exactly one cleanup commit lands, the branch moves
 * once (its reflog gains one entry: the loser's update never overwrote the winner's), {@code origin}
 * carries that one commit, and neither instance fails. Through the real entry the second writer in
 * practice reads {@code Delivered}: the clone's transfers serialize the two before the commit, so
 * the compare-and-swap loss itself is pinned by {@code DeliveredCleanupSpec} in {@code :application}.
 *
 * <p>The reflog this spec counts exists because the registered clone is not bare: git turns {@code
 * core.logAllRefUpdates} on for a repository with a working tree, so every update of a head under
 * {@code refs/heads/} — the adoption's {@code create} included — is logged (design D9). A bare clone
 * would log nothing, and the count would prove nothing; the register, outside {@code refs/heads/},
 * has no reflog and is never counted.
 *
 * <p>Both media write the cleanup commit through the one bare-object writer (design D8): no worktree,
 * no index lock, so the two writers are decided at the ref transaction's compare-and-swap. The host
 * feature is the regression guard for task 3.3's finding, where the host writer removed the
 * directory in the shared task worktree and the loser met the winner's {@code index.lock} before
 * either reached the ref. Each feature races {@link #ROUNDS} fresh worlds, so a defect of that kind
 * is not a coin toss that one lucky interleaving turns green.
 *
 * <p>FR4, NFR-R1 of fix-terminal-receipt-convergence.
 */
class DeliveredCleanupRaceSpec extends Specification {

    /**
     * Rounds: which writer reaches git first is the scheduler's choice, so the feature races several
     * fresh worlds rather than trusting one interleaving.
     */
    private static final int ROUNDS = 5

    /** {@code take}'s exit code for a task it skips as already done ({@code TakeExitCodeMapper}). */
    private static final int SKIPPED = 15

    @TempDir
    Path tempDir

    // Scenario: tracker/tracker-take / Two instances converge on one cleanup commit
    def "FR4, NFR-R1: two instances taking one finished task at once land one cleanup commit (container)"() {
        expect: 'in every round, neither failed, one cleanup commit landed, the branch moved once, origin carries it'
        (1..ROUNDS).every { racedAndConverged(CONTAINER, it) }
    }

    // FR4, NFR-R1, M5: the host writers share the clone, not the task worktree — the cleanup commit
    //     is built over bare objects (design D8), so the loser can lose only at the compare-and-swap,
    //     after the winner's ref move landed, and its re-read finds Delivered (task 3.3's finding,
    //     2026-10-14, was the former worktree writer's index.lock one step earlier).
    // Scenario: tracker/tracker-take / Two instances converge on one cleanup commit
    def "FR4, NFR-R1: two instances taking one finished task at once land one cleanup commit (host)"() {
        expect: 'in every round, neither failed, one cleanup commit landed, the branch moved once, origin carries it'
        (1..ROUNDS).every { racedAndConverged(HOST, it) }
    }

    /** One round on a fresh world: a finished task whose branch still owes its cleanup, taken twice at once. */
    private boolean racedAndConverged(String medium, int round) {
        def world = new DeliveredCleanupWorld(Files.createDirectories(tempDir.resolve("round-${round}")), medium)
        world.completedUncleaned()
        world.trackerFinished()
        int movesBefore = moves(world)
        converged(world, race(world), movesBefore)
    }

    /**
     * Two {@code take <ref>} commands, each over its own bundle as its own process would be. Both
     * meet at every tracker read — the tracker adapter each resolves holds each {@code fetchTask}
     * until the other has made the same one — so the two reach the branch
     * together rather than one command's startup apart.
     */
    private static List<Integer> race(DeliveredCleanupWorld world) {
        def entries = new DeliveredCleanupEntries(world)
        def meet = new CyclicBarrier(2)
        def instances = (1..2).collect {
            new FutureTask<Integer>({
                entries.take(TaskGitFixture.real(), new MeetingTracker(world.tracker, meet))
            } as Callable<Integer>)
        }
        instances.each {
            Thread.ofPlatform().name('delivered-cleanup-race').start(it)
        }
        instances.collect { it.get(60, TimeUnit.SECONDS) }
    }

    /** The world's tracker as one instance's adapter: each fetch waits for the other instance's. */
    private static final class MeetingTracker implements Tracker {

        @Delegate
        private final Tracker delegate
        private final CyclicBarrier meet

        MeetingTracker(Tracker delegate, CyclicBarrier meet) {
            this.delegate = delegate
            this.meet = meet
        }

        @Override
        TrackerTask fetchTask(TaskRef ref) {
            TrackerTask task = delegate.fetchTask(ref)
            meet.await(30, TimeUnit.SECONDS)
            task
        }
    }

    /**
     * Both instances report the task already done; one cleanup commit landed and the branch moved
     * once — the loser's update never overwrote the winner's; origin carries it; the workspace is gone.
     */
    private static boolean converged(DeliveredCleanupWorld world, List<Integer> exits, int movesBefore) {
        assert exits == [SKIPPED, SKIPPED]
        assert world.shape() == 'Delivered'
        assert world.subjects().count(ServiceCommitMessages.cleanup()) == 1
        assert moves(world) == movesBefore + 1
        assert world.originCarriesCleanup()
        assert world.workspaceGone()
        true
    }

    /** The updates the branch ref has recorded: one reflog entry per move. */
    private static int moves(DeliveredCleanupWorld world) {
        world.gitOutput(world.projectDir, 'reflog', 'show', '--format=%H', DeliveredCleanupWorld.branchRef()).readLines().size()
    }
}
