package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.app.RunOrder
import com.github.oinsio.gnomish.app.TakeOrder
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
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
 */
class TakeKillPointWorld extends KillPointWorld {

    /** The pipeline the task was pinned to; the take order and the mechanics both carry it. */
    PipelineDefinition definition

    /** {@code (TakeOrder) -> TakeResult}: one pickup through the medium's real take routes. */
    Closure<TakeResult> routes

    /** {@code () -> int}: agent rounds the engine started in this world, pickups included. */
    Closure<Integer> agentRounds

    /** The take order of this world's claim over its own pinned pipeline. */
    @Override
    TakeOrder takeOrder() {
        def task = new TrackerTask(ref, new TaskSnapshot(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Working(instanceId.value()), AbortFacts.none(), false)
        new TakeOrder(new RunOrder(repoDir, null, definition, false), task, tracker, instanceId)
    }

    /**
     * The next {@code take} of this task, as the tracker admits one: a task the tracker reports
     * awaiting a human, with no tracker write owed by its branch, is claimed by nobody — a delivered
     * park waits for its human — so the pickup is empty. Every other state is the claim's own: the
     * routes run, once.
     */
    void pickup() {
        boolean parked = tracker.fetchTask(ref).state() instanceof TrackerTaskState.AwaitingHuman
        if (parked && tipTask()?.trackerWritePending() != Boolean.TRUE) {
            return
        }
        routes.call(takeOrder())
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

    /** The tip's recorded state, read through the wire's one mapper. */
    TaskState tipState() {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(tipStateJson())))
    }

    /** The escalation a recorded stop is parked with, rebuilt from the tip's last round as D3 rebuilds it. */
    EscalationReport recordedStop() {
        def stop = tipState().attempts().last().stop() as Stop.DecisionNeeded
        new EscalationReport.DecisionNeeded(stop.question(), stop.options())
    }

    /** Commit subjects of the task branch, oldest first, service commits included. */
    List<String> subjects() {
        gitOutput(repoDir, 'log', '--reverse', '--format=%s', "gnomish/${taskId}").readLines()
    }
}
