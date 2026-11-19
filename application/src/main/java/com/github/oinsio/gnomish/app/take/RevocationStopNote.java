package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The one "work stopped" note a revoked claim posts, on both media (FR15 of add-tracker-port): the
 * heading over the revocation reason, taken through the comment plane's fenced shape, then what
 * happened to the interrupted round's leftovers. The reason names what the tracker held — the new
 * claim's holder, a closure reason — so it leaves through the comment plane at the write, with the
 * factory's own sentences outside the fence (design D7 of type-untrusted-text); the fenced shape,
 * not the inline one, because the note is a heading over one capture (design D6, revised
 * 2026-09-19).
 *
 * <p>The last sentence names the decision {@code NoSalvageOverVerification} took: a salvage commit,
 * or, over a registered snapshot or pending commit, the discard (design D22 of
 * migrate-verify-to-executor-contract). One owner of the text, so the two media cannot report the
 * same stop differently.
 *
 * <p>Implements FR15 of add-tracker-port; FR19 of migrate-verify-to-executor-contract.
 */
public final class RevocationStopNote {

    private RevocationStopNote() {}

    /**
     * The note a revoked claim posts.
     *
     * @param reason the tracker-sourced revocation reason; never blank
     * @param keptOverVerification {@code true} when the register named an interrupted verification,
     *     so the leftovers were discarded rather than salvage-committed
     * @return the note to post; never blank
     */
    public static String of(String reason, boolean keptOverVerification) {
        String leftovers = keptOverVerification
                ? "\nA verification was interrupted above the registered snapshot: its leftovers were discarded, no"
                        + " salvage commit landed, and the branch was left in place for whoever resumes this task to"
                        + " verify the round again."
                : "\nUncommitted work was salvage-committed and the branch left in place for whoever resumes"
                        + " this task.";
        return "Work stopped:\n" + UntrustedText.tracker(reason).forComment() + leftovers;
    }
}
