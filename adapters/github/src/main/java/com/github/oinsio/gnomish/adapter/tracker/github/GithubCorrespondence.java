package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;

/**
 * Implements the two comment-only {@code Tracker} write operations for the
 * GitHub adapter that never change the logical state: {@code postNote}
 * (general-purpose correspondence, {@code note}-kind structural marker —
 * {@link GithubMarkerKind#NOTE}'s first real use) and {@code release}
 * (drop claim, tracker state untouched).
 *
 * <p>Judgment call (task 4.14) on {@code release}: per FR15/design D2,
 * revocation already performs its own salvage protocol — a best-effort
 * push and a structural "work stopped" note — before calling {@code
 * release}; by the time {@code release} runs, whatever correspondence the
 * situation needed has already been posted, and the issue may already be
 * closed or relabeled by a human. GitHub's coordination substrate is only
 * the working label plus the claim comment; there is no separate "claim
 * record" to delete once the claim comment has already served its purpose
 * in the lease race (task 4.11's earliest-id decision is final the moment
 * it is made). Touching a label or posting another comment here would
 * violate "state untouched" (FR15) and risks mutating an issue a human has
 * already taken back — e.g. re-adding a label the human just removed. The
 * GitHub adapter therefore implements {@code release} as an explicit no-op:
 * no HTTP call, documented here rather than left as a silent gap.
 *
 * <p>Implements FR1, FR14 of add-tracker-port; FR11 of harden-task-branch-contract.
 */
// Not a record: this is a behavior-bearing correspondence service (a collaborator wrapping the
// shared marker writer, not immutable data), kept as a plain final class.
@SuppressWarnings("ClassCanBeRecord")
public final class GithubCorrespondence {

    private final GithubMarkerWriter markerWriter;

    public GithubCorrespondence(GithubMarkerWriter markerWriter) {
        this.markerWriter = markerWriter;
    }

    /**
     * Implements {@code Tracker.postNote} for GitHub (NFR-O1), through the shared marker writer
     * (FR11). A note is the claimless correspondence path — the revocation salvage posts one after
     * the claim was already dropped — so its identity is scoped by the note's own text rather than
     * by a tenure it does not have: re-driving the same note updates it, a different note is a
     * different comment.
     */
    public void postNote(TaskRef ref, String text) {
        markerWriter.write(GithubTaskId.parse(ref.id()), GithubMarkerKind.NOTE, text, null);
    }

    /**
     * Implements {@code Tracker.release} for GitHub as an explicit no-op
     * (design D2, FR15 "state untouched" — see class Javadoc for the full
     * reasoning). {@code ref} is unused but kept in the signature to satisfy
     * the {@code Tracker} port contract.
     */
    @SuppressWarnings("unused")
    public void release(TaskRef ref) {
        // Intentional no-op: see class Javadoc.
    }
}
