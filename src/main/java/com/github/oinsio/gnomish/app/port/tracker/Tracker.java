package com.github.oinsio.gnomish.app.port.tracker;

import java.util.List;

/**
 * The application layer's single abstraction over any task tracker: exactly
 * the ten v1 operations of feed, coordination, and correspondence (design D1
 * sketch), speaking only the factory's own vocabulary — tasks, states,
 * decisions, abort facts — never a tracker-specific concept such as a label or
 * an issue. All such mapping is confined to adapters under {@code
 * adapter.tracker.*}; core compiles and is tested against this interface
 * alone (tracker-port spec, "Single Tracker port speaking the factory's
 * language").
 *
 * <p>Transitions between the logical task states ({@link TrackerTaskState})
 * are initiated only by the factory or by a human acting in the tracker UI —
 * never by the gnome process itself (FR2). Report rendering (an engine domain
 * report turned into text) happens in core: methods that carry a report or
 * note accept finished text plus structural fields, never an engine domain
 * model (FR1).
 *
 * <p>Implements FR1 of add-tracker-port.
 */
public interface Tracker {

    /**
     * Returns unclaimed tasks in adapter queue order, each paired with its
     * {@link AbortFacts}. This is the raw feed only: it does NOT filter by abort
     * backoff — deciding whether an entry with unexpired backoff should be
     * skipped by the bare auto {@code take} is core policy applied over the
     * adapter-reported facts, never the adapter's own job (FR1, FR10, design
     * D10).
     *
     * <p>Implements FR1, FR10 of add-tracker-port.
     *
     * @param limit the maximum number of entries to return; must be positive
     * @return unclaimed tasks in adapter queue order, possibly empty; never null
     */
    List<ReadyTask> listReady(int limit);

    /**
     * Returns the full fact set for one task: its frozen {@link TaskSnapshot},
     * current {@link TrackerTaskState} (carrying the claim holder for {@code
     * Working} or the reason for {@code AwaitingHuman}), and {@link AbortFacts}.
     * A closed or nonexistent task is reported with state {@link
     * TrackerTaskState.Gone}, never thrown as an exception (tracker-port spec,
     * "Closed task is Gone").
     *
     * <p>Implements FR1 of add-tracker-port.
     *
     * @param ref the task's canonical identity; never null
     * @return the task's current fact set; never null
     */
    TrackerTask fetchTask(TaskRef ref);

    /**
     * Returns human reply comments posted after the factory's last decision ack,
     * in posting order. Empty means either no human has replied yet or the most
     * recent reply has already been consumed by {@link
     * #acknowledgeDecision(TaskRef, String)} — the two are indistinguishable to
     * this method by design, since a stale reply must never resurface after an
     * ack (tracker-port spec, "Decision collection anchored to the last ack").
     *
     * <p>Implements FR12 of add-tracker-port.
     *
     * @param ref the task's canonical identity; never null
     * @return replies posted since the last ack, in posting order, possibly
     *     empty; never null
     */
    List<HumanReply> collectDecisions(TaskRef ref);

    /**
     * Attempts to claim {@code ref} for {@code instanceId}. Every adapter's
     * implementation SHALL be observably atomic: in a concurrent race exactly one
     * caller receives {@link ClaimResult.Acquired}, and every other caller
     * receives {@link ClaimResult.Held} naming the winner (NFR-R1). A successful
     * claim transitions the task to {@code Working(instanceId)}.
     *
     * <p>{@code instanceId} is a plain {@link String}: callers pass the flattened
     * {@link InstanceId#value()} form ({@code <name>-<suffix>}), not the composite
     * {@link InstanceId} type — the id is an informational label, never a
     * coordination primitive (design D13), so the port stays agnostic to its
     * structure.
     *
     * <p>Implements FR1 of add-tracker-port.
     *
     * @param ref the task's canonical identity; never null
     * @param instanceId the claiming instance's identifier; never blank
     * @return {@link ClaimResult.Acquired} if the claim succeeded, {@link
     *     ClaimResult.Held} naming the current holder otherwise; never null
     */
    ClaimResult claim(TaskRef ref, String instanceId);

    /**
     * Drops the caller's claim on {@code ref} without changing its logical
     * state otherwise — used on a revoked or abandoned task where the tracker
     * state itself must be left untouched for a human to inspect (design D2,
     * FR15).
     *
     * <p>Implements FR1 of add-tracker-port.
     *
     * @param ref the task's canonical identity; never null
     */
    void release(TaskRef ref);

    /**
     * Transitions {@code ref} to {@link TrackerTaskState.AwaitingHuman} with
     * {@code reason}, publishing {@code report} as the finished text a human
     * reads to understand what happened and how to return the task (FR1, FR13,
     * UX3). This is a factory-initiated transition only; the only exits from
     * {@code AwaitingHuman} are human actions.
     *
     * <p>Implements FR1 of add-tracker-port.
     *
     * @param ref the task's canonical identity; never null
     * @param reason why the task is being parked; never null
     * @param report finished text describing the situation and the return path;
     *     never blank
     */
    void park(TaskRef ref, ParkReason reason, String report);

    /**
     * Transitions {@code ref} to {@link TrackerTaskState.Finished}, publishing
     * {@code summary} as the final report a human reviews (stages, attempts,
     * branch link, usage — FR18). Once finished, the task is never touched
     * again; re-running it later is a new task, not a resume (FR18, NG7).
     *
     * <p>Implements FR1 of add-tracker-port.
     *
     * @param ref the task's canonical identity; never null
     * @param summary finished text of the final report; never blank
     */
    void finish(TaskRef ref, String summary);

    /**
     * Persists {@code record} as a structural abort marker and returns {@code
     * ref} to {@link TrackerTaskState.Ready}, as one operation. The marker
     * SHALL be reconstructable by any instance from the tracker alone: after
     * this call, a {@code fetchTask} or {@code listReady} from a different
     * instance observes the updated {@link AbortFacts} (count and last abort
     * time) derived from it (NFR-R3, tracker-port spec, "Abort facts round-trip
     * across instances"). This method never applies backoff or the K-abort fuse
     * policy itself — deciding whether the fuse trips instead of a plain abort
     * is core's job over the facts this call produces (design D10).
     *
     * <p>Implements FR14 of add-tracker-port.
     *
     * @param ref the task's canonical identity; never null
     * @param record the abort marker to persist; never null
     */
    void recordAbort(TaskRef ref, AbortRecord record);

    /**
     * Posts an "acting on decision" marker naming {@code decisionText}, such
     * that a subsequent {@link #collectDecisions(TaskRef)} on the same task is
     * empty until a new human reply arrives. This is the single mechanism that
     * both records which reply the factory acted on and anchors future decision
     * collection to that point (FR12, UX3).
     *
     * <p>Implements FR12 of add-tracker-port.
     *
     * @param ref the task's canonical identity; never null
     * @param decisionText the decision being acted on, carried verbatim into the
     *     ack; never blank
     */
    void acknowledgeDecision(TaskRef ref, String decisionText);

    /**
     * Posts {@code text} as a note on {@code ref} without changing its logical
     * state — the general-purpose correspondence operation for anything that is
     * neither a park report, a finish summary, nor a decision ack (e.g. an
     * intermediate status note, NFR-O1).
     *
     * <p>Implements FR1 of add-tracker-port.
     *
     * @param ref the task's canonical identity; never null
     * @param text finished text to post; never blank
     */
    void postNote(TaskRef ref, String text);
}
