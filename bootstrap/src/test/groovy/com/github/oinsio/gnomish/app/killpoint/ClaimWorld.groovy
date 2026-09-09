package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.app.lease.Reaper
import com.github.oinsio.gnomish.app.lease.StalenessMemory
import com.github.oinsio.gnomish.app.lease.TrackerShapeClassifier
import com.github.oinsio.gnomish.app.lease.VirtualMonotonicTime
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import java.nio.file.Path
import java.time.Duration

/**
 * One task under the claim kill-point run: the window design D8 of add-base-ref-resolution opens
 * between the tracker claim and the task-creation commit — the window the base refresh lengthens.
 *
 * <p>A world of its own rather than {@link KillPointWorld} or {@link CreationWorld}, because this
 * window straddles two media and its frozen state is a fact about both: the tracker holds a live
 * claim whose holder is dead, and the branch medium holds <em>nothing at all</em> — no ref, hence
 * not a {@code task-branch-contract} shape and nothing for that capability's recovery to converge.
 * Reporting one label per medium is what makes the branch's absence an assertion rather than a
 * comment, at the freeze and again after the pickup.
 *
 * <p>The recovery owner is therefore the tracker's, not the branch's: the reaper the {@code
 * claim-heartbeat} capability names for {@code Claimed}, driven here on virtual time so the TTL
 * elapses instantly. The next claimant is not a recovery owner — it takes the restored {@code
 * Ready} task through the ordinary lease, and because nothing durable anywhere references the
 * dead holder's resolution, its fresh claim resolves and refreshes the base from scratch (that
 * route from this exact premise — {@code Ready}, no branch — is {@code TakeDispositionSpec}'s
 * "Ready with no existing branch" scenario over {@code FreshClaimBaseBindingSpec}'s resolve step).
 */
class ClaimWorld implements BareGitRepoFixture {

    /** How long a claim may stand unchanged before the reaper judges it stale. */
    private static final Duration TTL = Duration.ofMinutes(15)

    /** Facts no task in this world can hold, so {@code repairIndex} always takes its read-only arm. */
    private static final TrackerFacts IMPOSSIBLE = new TrackerFacts(
    new StateLabels(false, false, false, false, true),
    new ClaimFacts.Dead('no-instance-ever-held-this'),
    BoundaryKind.ABORT)

    /** The bare {@code origin}: the fleet's only view of the branch medium, and here an empty one. */
    Path origin

    /** The claiming instance's clone — where a task branch WOULD be cut, had the instance lived. */
    Path claimantClone

    String taskId

    TaskRef ref

    InstanceId instanceId

    InMemoryTracker tracker

    /** The reaper of another instance, over its own staleness memory on virtual time. */
    Reaper reaper

    VirtualMonotonicTime time

    /** Wires the reaper this world's pickup runs; called once the tracker is in place. */
    void armReaper() {
        time = new VirtualMonotonicTime()
        reaper = new Reaper(tracker, new StalenessMemory(time, TTL))
    }

    /**
     * One tracker shape and one branch-medium fact, because the window freezes both: {@code
     * Claimed/no-branch} at the kill, {@code Ready/no-branch} once the reaper has converged it.
     */
    String shape() {
        "${trackerShape()}/${branchPresence()}"
    }

    /** The task branch's name, if any instance ever cut one. */
    String branch() {
        "gnomish/${taskId}"
    }

    /** Whether the task branch exists on {@code origin} or on the dead claimant's own clone. */
    boolean anyBranch() {
        gitExitCode(origin, 'rev-parse', '--verify', '--quiet', "refs/heads/${branch()}") == 0 ||
                gitExitCode(claimantClone, 'rev-parse', '--verify', '--quiet', "refs/heads/${branch()}") == 0
    }

    /**
     * The reaper's own recovery, run exactly once: two ticks with the TTL elapsing between them,
     * which is what the {@code claim-heartbeat} capability requires before a claim first seen this
     * tick may be judged stale. Run again on the converged state it lists no open task and does
     * nothing — the idempotence the harness asserts.
     */
    void reap() {
        reaper.reapOnce([])
        time.advance(TTL)
        reaper.reapOnce([])
    }

    /**
     * What a second reaper pass must leave untouched: the tracker state and claim footprint, the
     * task's membership of the ready queue the next claimant draws from, and the branch medium's
     * emptiness.
     */
    Map fingerprint() {
        [
            tracker: tracker.fetchTask(ref).state().toString(),
            claim: sweepFacts()?.claim()?.toString(),
            queued: tracker.listReady(50).any { it.ref() == ref },
            branch: anyBranch(),
        ]
    }

    /** The production classifier's answer over the facts the sweep itself reads. */
    private String trackerShape() {
        def facts = sweepFacts()
        facts == null ? 'Gone' : TrackerShapeClassifier.classify(facts).class.simpleName
    }

    private String branchPresence() {
        anyBranch() ? 'branch' : 'no-branch'
    }

    /**
     * The facts the adapter itself reports, read through the port rather than rebuilt from the
     * listings. {@code listOpen} carries them for a held task; once the reaper has returned the
     * task to the queue it is no longer open, and {@code listReady}'s entry deliberately does not
     * distinguish a reaper-returned task from a human-returned one — reconstructing facts from it
     * would decide the very question the classifier exists to answer.
     *
     * <p>So the ready case reads them the one way the port offers: {@code repairIndex} handed
     * facts that cannot match, whose documented answer is {@code Unchanged(current)} — a pure read,
     * no entry appended, no state touched. {@link #IMPOSSIBLE} is a closed task holding a dead
     * claim of a holder no world here ever has, so the matching branch is unreachable.
     */
    private TrackerFacts sweepFacts() {
        def open = tracker.listOpen().find { it.ref() == ref }
        if (open != null) {
            return open.facts()
        }
        def repair = tracker.repairIndex(ref, IMPOSSIBLE)
        repair instanceof RepairIndexResult.Unchanged ? repair.facts() : null
    }
}
