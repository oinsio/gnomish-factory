package com.github.oinsio.gnomish.adapter.tracker.inmemory;

/**
 * One line of a task's tracker-thread history (UX4 of add-tracker-port: "The tracker issue thread
 * alone tells the story of a task: claim, reports, decisions, acks, aborts, final summary —
 * readable without access to factory logs"). {@link InMemoryTracker} appends one entry per
 * coordination write ({@code claim}, {@code park}, {@code finish}, {@code recordAbort}, {@code
 * acknowledgeDecision}, {@code postNote}, {@code recordProgress}, {@code heartbeat}, and stale-claim
 * removal) so a test — or a future
 * operator-facing rendering — can read the whole story back in order, exactly as a human would
 * scroll a real tracker's comment feed. {@code release} carries no fact worth narrating (design
 * D2: it deliberately leaves the logical state untouched) and appends nothing.
 *
 * <p>Deliberately NOT part of the {@link com.github.oinsio.gnomish.app.port.tracker.Tracker} port
 * (FR1 keeps the port at exactly its fourteen operations — ten from add-tracker-port,
 * {@code recordProgress} from design D1 of fix-abort-progress-reset, and the lease-maintenance
 * trio {@code listOpen}/{@code heartbeat}/{@code removeStaleClaim} from add-claim-heartbeat):
 * this is an in-memory-adapter-only implementation detail, exposed
 * read-only via {@link InMemoryTrackerHarness#thread}.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1, FR3, FR18, M3, UX4 of add-tracker-port.
 *
 * @param kind which coordination write produced this entry
 * @param text a short, human-readable one-line narration of the fact recorded; never blank
 */
public record CorrespondenceEntry(Kind kind, String text) {

    public CorrespondenceEntry {
        if (text.isBlank()) {
            throw new IllegalArgumentException("CorrespondenceEntry.text must not be blank");
        }
    }

    /** Which port write produced a {@link CorrespondenceEntry}, mirroring the tracker thread's own vocabulary. */
    public enum Kind {
        CLAIM,
        PARK,
        FINISH,
        ABORT,
        ACK,
        NOTE,
        PROGRESS,
        HEARTBEAT,
        STALE_CLAIM_REMOVED,
        INDEX_REPAIR
    }
}
