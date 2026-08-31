package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;

/**
 * A non-steady tracker shape the observation memory has just released for repair: the task's {@link
 * TaskRef} paired with the exact {@link TrackerShape} that stood past its timer — a claim version
 * unchanged for the TTL, or a window shape unchanged for the window grace. The reaper hands both to
 * the port operation that shape's recovery names — stale-claim removal for an abandoned footprint
 * or a dead tenure, index repair for a pending claim or a lagging index — and the shape doubles as
 * the guard the adapter re-checks, so racing repairs converge.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 *
 * @param ref the task's canonical identity; never null
 * @param facts the facts observed, handed back as the repair's guard; never null
 * @param shape the shape those facts classified to; never null
 */
public record TrackerRepair(TaskRef ref, TrackerFacts facts, TrackerShape shape) {}
