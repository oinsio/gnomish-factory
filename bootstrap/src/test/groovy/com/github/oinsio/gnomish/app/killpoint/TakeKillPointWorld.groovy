package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.tracker.inmemory.CorrespondenceEntry
import com.github.oinsio.gnomish.app.KillPointTakeRoutes
import com.github.oinsio.gnomish.app.RunOrder
import com.github.oinsio.gnomish.app.TakeOrder
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * A kill-point world whose pickup is a real {@code take}: the medium's own lifecycle writer and
 * round writer, a factory clone with an {@code origin} the resume path resolves its pinned base
 * against, and the take routes of that medium over the pipeline the task was pinned to (NFR-R1,
 * design D8 of make-checkpoint-gate-durable).
 *
 * <p>{@link #agentRounds} is the recording executor: the number of agent rounds the world has seen
 * the engine start since it was built — the fake agent's own invocation log on the host, the
 * scripted docker's agent execs in the container. A gate row asserts it stays zero.
 *
 * <p>Two instances take this task, as production's do (design D3 of
 * fix-terminal-receipt-convergence). The world's own instance seeded the claim and dies at the
 * kill; {@link #pickup} resumes under its claim, for the rows whose premise is that instance's own
 * return ({@link #returnedAndReclaimed}). {@link #SECOND} is the instance that takes the task over:
 * {@link #takeAsSecondInstance} is the next {@code take} as the tracker admits one — the reaper
 * returns the dead claim, the real {@code tracker.claim} grants the second its own tenure, recorded
 * in its own book, and the routes or the finished-task disposition run under its order. A pickup
 * that skipped the claim would read a state no production pickup reads, which is how the
 * matrix once passed over a probe that re-parked every returned task.
 */
class TakeKillPointWorld extends KillPointWorld {

    /** The instance that takes the task over after the world's own instance died. */
    static final InstanceId SECOND = new InstanceId('gnomish-factory', 'kp0004')

    /** The pipeline the task was pinned to; the take order and the mechanics both carry it. */
    PipelineDefinition definition

    /** The medium's take entries over this world's own bundle: the seeding instance's. */
    KillPointTakeRoutes.Entries routes

    /** The second instance's bundle, whose tenure book its claim fills and its writers stamp from. */
    TaskGit secondGit

    /** The medium's take entries over {@link #secondGit}. */
    KillPointTakeRoutes.Entries secondRoutes

    /** {@code () -> int}: agent rounds the engine started in this world, pickups included. */
    Closure<Integer> agentRounds

    /** Whether a human answered a landed park by returning the task ({@link #pickUpAfterKill}). */
    boolean humanReturned

    private int pickups

    /** The take order of this world's claim over its own pinned pipeline. */
    @Override
    TakeOrder takeOrder() {
        def task = new TrackerTask(ref, new TaskSnapshot(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Working(instanceId.value()), AbortFacts.none(), false)
        new TakeOrder(new RunOrder(repoDir, null, definition, false), task, tracker, instanceId)
    }

    /**
     * The seeding instance resumes under its own claim: the routes run, once, whatever the tracker
     * says. A row whose second pass must find nothing to take says so itself ({@link #parked}).
     */
    void pickup() {
        routes.route.call(takeOrder())
    }

    /** Whether the tracker reports the task awaiting a human: no {@code take} claims it then. */
    boolean parked() {
        tracker.fetchTask(ref).state() instanceof TrackerTaskState.AwaitingHuman
    }

    /**
     * One pickup after the kill, as production sequences it: on the first pickup, a park that had
     * already landed is answered by its human, who returns the task; then {@link
     * #takeAsSecondInstance}. A park the pickup itself delivers waits for a human this row never
     * plays, so a later pickup finds nothing to take.
     */
    void pickUpAfterKill() {
        if (pickups++ == 0 && parked()) {
            trackerHarness.returnToReady(ref)
            humanReturned = true
        }
        takeAsSecondInstance()
    }

    /**
     * The next {@code take} of this task by {@link #SECOND}, as the tracker admits one: a parked task
     * is claimed by nobody; a finished one goes to the disposition, unclaimed; a claim the dead
     * instance still holds is reaped first; then the second claims and the routes run.
     */
    void takeAsSecondInstance() {
        def state = tracker.fetchTask(ref).state()
        if (state instanceof TrackerTaskState.AwaitingHuman) {
            return
        }
        if (state instanceof TrackerTaskState.Finished) {
            secondRoutes.dispose.call(secondOrder())
            return
        }
        if (state == new TrackerTaskState.Working(instanceId.value())) {
            reap()
        }
        secondRoutes.route.call(claimAsSecondInstance())
    }

    /** The reaper's write: the dead instance's stale claim removed, the task back to {@code Ready}. */
    void reap() {
        def claim = tracker.listOpen().find { it.ref() == ref }.facts().claim()
        assert tracker.removeStaleClaim(ref, claim) instanceof RemoveStaleClaimResult.Removed
    }

    /**
     * {@link #SECOND} claims the task through the real {@code tracker.claim}, its tenure recorded in
     * its own book before any write, as the live claim path records it.
     *
     * @return the second instance's take order over the task as the tracker now reports it
     */
    TakeOrder claimAsSecondInstance() {
        def claimed = tracker.claim(ref, SECOND.value())
        assert claimed instanceof ClaimResult.Acquired: "the second instance could not claim: ${claimed}"
        secondGit.epochs().issued(taskId, claimed.epoch())
        secondOrder()
    }

    /**
     * A human answers the delivered park by returning the task, and this instance claims it again —
     * the tenure the claim opens recorded first, as the live claim path records it.
     */
    void returnedAndReclaimed() {
        trackerHarness.returnToReady(ref)
        trackerHarness.seedWorkingWithClaim(tracker, ref, instanceId.value())
        epochs.issued(taskId, tracker.listOpen().find {
            it.ref() == ref
        }.facts().claim().liveVersion().epoch())
    }

    /**
     * The parks the tracker thread records before and after {@link #SECOND}'s first claim, as
     * {@code [before, after]}: what a returned pickup's row accounts for — the one park the human
     * answered, then only the parks its own continuation produced.
     */
    List<Integer> parksAroundTakeover() {
        def thread = trackerHarness.thread(ref)
        int takeover = thread.findIndexOf {
            it.kind() == CorrespondenceEntry.Kind.CLAIM && it.text().contains(SECOND.value())
        }
        def parks = { List entries ->
            entries.count {
                it.kind() == CorrespondenceEntry.Kind.PARK
            } as int
        }
        takeover < 0 ? [parks(thread), 0] : [
            parks(thread.take(takeover)),
            parks(thread.drop(takeover))
        ]
    }

    /** The tip's recorded state, read through the wire's one mapper. */
    TaskState tipState() {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(tipStateJson())))
    }

    /** The escalation a recorded stop is parked with, rebuilt from the tip's last round as D3 rebuilds it. */
    EscalationReport recordedStop() {
        def stop = tipState().attempts().last().stop() as Stop.DecisionNeeded
        new EscalationReport.DecisionNeeded(stop.question(), stop.options())
    }

    private TakeOrder secondOrder() {
        new TakeOrder(new RunOrder(repoDir, null, definition, false), tracker.fetchTask(ref), tracker, SECOND)
    }

    /** Commit subjects of the task branch, oldest first, service commits included. */
    List<String> subjects() {
        gitOutput(repoDir, 'log', '--reverse', '--format=%s', "gnomish/${taskId}").readLines()
    }
}
