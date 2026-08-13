package com.github.oinsio.gnomish.app.port.tracker;

import java.util.List;

/**
 * The application layer's single abstraction over any task tracker: fifteen operations of
 * feed, coordination, and correspondence (design D1 of add-tracker-port; the lease-maintenance
 * trio {@link #listOpen()}, {@link #heartbeat(TaskRef, String)}, {@link
 * #removeStaleClaim(TaskRef, ClaimVersion)} added by add-claim-heartbeat). Speaks only the
 * factory's own vocabulary — tasks, states, decisions, abort facts, claim versions — never a
 * tracker-specific concept; mapping is confined to adapters under {@code adapter.tracker.*}.
 * Transitions between {@link TrackerTaskState}s are initiated only by the factory or by a human
 * in the tracker UI, never by the gnome (FR2). Methods carrying a report or note accept finished
 * text plus structural fields, never an engine domain model (FR1).
 * <p>Implements FR1 of add-tracker-port.
 */
public interface Tracker {

    /**
     * Returns up to {@code limit} unclaimed tasks in adapter queue order, each
     * paired with its {@link AbortFacts} and title. Raw feed only: abort-backoff
     * filtering is core policy over these facts, never the adapter's job (FR1,
     * FR10, D10). The title is populated from data the list call already
     * receives — never a per-task {@code fetchTask} fan-out (FR7, NFR-P1 of
     * add-board-command).
     * <p>Implements FR1, FR10 of add-tracker-port. Implements FR7, NFR-P1 of
     * add-board-command.
     * @param limit the maximum number of entries to return; must be positive
     * @return unclaimed tasks in queue order, possibly empty; never null
     */
    List<ReadyTask> listReady(int limit);

    /**
     * Returns one task's full fact set: {@link TaskSnapshot}, {@link
     * TrackerTaskState}, and {@link AbortFacts}. A closed or nonexistent task is
     * reported with state {@link TrackerTaskState.Gone}, never thrown (FR1).
     * <p>Implements FR1 of add-tracker-port.
     * @param ref the task's canonical identity; never null
     * @return the task's current fact set; never null
     */
    TrackerTask fetchTask(TaskRef ref);

    /**
     * Returns human reply comments posted after the factory's last decision ack, in posting
     * order. Empty means no new reply since the last {@link #acknowledgeDecision(TaskRef,
     * String)} — a stale reply must never resurface after an ack (FR12).
     * <p>Implements FR12 of add-tracker-port.
     * @param ref the task's canonical identity; never null
     * @return replies since the last ack, in posting order, possibly empty; never null
     */
    List<HumanReply> collectDecisions(TaskRef ref);

    /**
     * Attempts to claim {@code ref} for {@code instanceId}. SHALL be observably
     * atomic: in a race exactly one caller gets {@link ClaimResult.Acquired}, the
     * rest {@link ClaimResult.Held} naming the winner (NFR-R1). Success transitions
     * to {@code Working(instanceId)}. {@code instanceId} is the flattened {@link
     * InstanceId#value()} — an informational label, never a coordination primitive
     * (design D13).
     * <p>Implements FR1 of add-tracker-port.
     * @param ref the task's canonical identity; never null
     * @param instanceId the claiming instance's identifier; never blank
     * @return {@link ClaimResult.Acquired} on success, else {@link ClaimResult.Held}; never null
     */
    ClaimResult claim(TaskRef ref, String instanceId);

    /**
     * Drops the caller's claim on {@code ref} without otherwise changing its
     * logical state — for a revoked or abandoned task left for a human to inspect
     * (design D2, FR15).
     * <p>Implements FR1 of add-tracker-port.
     * @param ref the task's canonical identity; never null
     */
    void release(TaskRef ref);

    /**
     * Transitions {@code ref} to {@link TrackerTaskState.AwaitingHuman} with {@code
     * reason}, publishing {@code report} as the text a human reads to return the
     * task (FR1, FR13, UX3). Factory-initiated only; exits are human actions.
     * <p>Implements FR1 of add-tracker-port.
     * @param ref the task's canonical identity; never null
     * @param reason why the task is being parked; never null
     * @param report finished text describing the situation and return path; never blank
     */
    void park(TaskRef ref, ParkReason reason, String report);

    /**
     * Transitions {@code ref} to {@link TrackerTaskState.Finished}, publishing
     * {@code summary} as the final report (FR18). Once finished the task is never
     * touched again; re-running is a new task, not a resume (FR18, NG7).
     * <p>Implements FR1 of add-tracker-port.
     * @param ref the task's canonical identity; never null
     * @param summary finished text of the final report; never blank
     */
    void finish(TaskRef ref, String summary);

    /**
     * Declines a Ready task whose history already has a finish report — a terminal task a human
     * reopened — restoring its terminal status and explaining why (FR4). Design D5: SHALL
     * restore status FIRST, only then post {@code message}, bounding duplicate comments under a
     * concurrent-decline race (NFR-R1, NFR-R2). Idempotent (NFR-R1): if {@code ref} is already
     * terminal, a no-op — status unchanged, {@code message} NOT posted. Out-of-band like {@link
     * #postNote(TaskRef, String)}: never a park report or finish summary, no derivation weight
     * for {@code returned}/{@code finished} (D1, D3).
     * <p>Implements FR4 of enforce-finish-terminality.
     * @param ref the task's canonical identity; never null
     * @param message finished decline explanation composed by core; never blank
     */
    void declineFinished(TaskRef ref, String message);

    /**
     * Persists {@code record} as a structural abort marker and returns {@code ref} to {@link
     * TrackerTaskState.Ready}, as one operation. The marker SHALL be reconstructable from the
     * tracker alone: another instance's {@code fetchTask}/{@code listReady} observes the
     * updated {@link AbortFacts} (NFR-R3). Never applies backoff or the K-abort fuse — that is
     * core's job over these facts (D10).
     * <p>Implements FR14 of add-tracker-port.
     * @param ref the task's canonical identity; never null
     * @param record the abort marker to persist; never null
     */
    void recordAbort(TaskRef ref, AbortRecord record);

    /**
     * Persists a structural durable-progress marker for {@code ref}, leaving its logical state
     * and claim holder untouched. Reconstructable from the tracker alone: another instance then
     * observes {@link AbortFacts} reset to "aborts since this marker" (FR1, FR4, D1). Idempotent
     * within a claim: a second marker is harmless since reconstruction anchors to the latest
     * (NFR-R2).
     * <p>Implements FR1 of fix-abort-progress-reset.
     * @param ref the task's canonical identity; never null
     */
    void recordProgress(TaskRef ref);

    /**
     * Posts an "acting on decision" marker naming {@code decisionText}, so a later {@link
     * #collectDecisions(TaskRef)} is empty until a new human reply arrives — the single
     * mechanism that both records the acted-on reply and anchors future collection (FR12, UX3).
     * <p>Implements FR12 of add-tracker-port.
     * @param ref the task's canonical identity; never null
     * @param decisionText the decision being acted on, carried verbatim; never blank
     */
    void acknowledgeDecision(TaskRef ref, String decisionText);

    /**
     * Posts {@code text} as a note on {@code ref} without changing its logical state — the
     * general-purpose correspondence op for anything that is neither a park report, finish
     * summary, nor decision ack (NFR-O1).
     * <p>Implements FR1 of add-tracker-port.
     * @param ref the task's canonical identity; never null
     * @param text finished text to post; never blank
     */
    void postNote(TaskRef ref, String text);

    /**
     * Returns the open tasks — {@link TrackerTaskState.Working} or {@link
     * TrackerTaskState.AwaitingHuman} — each with its state, title and, for a {@code Working}
     * task carrying a live claim marker, its opaque {@link ClaimVersion}. {@code
     * Ready}/{@code Finished}/{@code Gone} never appear. Unlike {@link #listReady(int)} this
     * takes no limit: the reaper needs the full open set. Adapters report version facts only;
     * TTL policy and staleness judgment live in core (FR5, design D2, D4). The title comes from
     * data the list call already receives — never a per-task {@code fetchTask} fan-out (FR7,
     * NFR-P1 of add-board-command).
     * <p>Implements FR5 of add-claim-heartbeat. Implements FR7, NFR-P1 of add-board-command.
     * @return the open tasks with states and claim versions, possibly empty; never null
     */
    List<OpenTask> listOpen();

    /**
     * Updates the caller's claim marker on {@code ref} in place — refreshing its version and
     * writing {@code progressPayload} — without creating a new artifact (design D1). Returns
     * {@link HeartbeatResult.Beaten} with the refreshed {@link ClaimVersion}, or {@link
     * HeartbeatResult.ClaimGone} when the marker is gone (reaped or taken over) — a protocol
     * signal, not an error. An infrastructure failure is retryable and thrown, never a result,
     * so an outage is never confused with a lost claim (FR8, design D7).
     * <p>Implements FR5, FR8 of add-claim-heartbeat.
     * @param ref the task's canonical identity; never null
     * @param progressPayload finished text to write into the claim marker; never blank
     * @return {@link HeartbeatResult.Beaten} with the refreshed version, or {@link
     *     HeartbeatResult.ClaimGone} when the claim is lost; never null
     */
    HeartbeatResult heartbeat(TaskRef ref, String progressPayload);

    /**
     * Returns a stale claim to circulation as one operation, given {@code ref} and the {@code
     * observedVersion} judged stale: records a structural holder-transition marker naming the
     * dead holder, removes the dead marker, and transitions {@code ref} back to {@link
     * TrackerTaskState.Ready}. Never claims for the caller (design D5, D9, FR4). {@code
     * observedVersion} is a guard: a mismatch against the live claim is a safe no-op returning
     * {@link RemoveStaleClaimResult.Mismatch} with the current version (NFR-R2); a match yields
     * {@link RemoveStaleClaimResult.Removed}. The marker SHALL be reconstructable from the
     * tracker alone.
     * <p>Implements FR4, FR5 of add-claim-heartbeat.
     * @param ref the task's canonical identity; never null
     * @param observedVersion the stale claim version the caller observed; never null
     * @return {@link RemoveStaleClaimResult.Removed} on cleanup, or {@link
     *     RemoveStaleClaimResult.Mismatch} when the version no longer matched; never null
     */
    RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion);
}
