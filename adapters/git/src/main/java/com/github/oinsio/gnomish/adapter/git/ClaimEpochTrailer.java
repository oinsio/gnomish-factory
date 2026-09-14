package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import org.jspecify.annotations.Nullable;

/**
 * The claim epoch as it lives in a commit message: a git trailer line appended to the service
 * subject, so every commit of a tenure says which tenure made it (design D6, FR13). The trailer is
 * the branch's record of provenance: it says which tenure wrote the commit, and nothing reads it
 * back to classify the branch — a tip is judged on its content alone (fix-claim-epoch-fence FR1,
 * FR3). The real fences are the fast-forward-only push and the round-boundary revocation check.
 *
 * <p>A trailer rather than a subject suffix, for two reasons: the subject stays the exact string
 * {@link ServiceCommitMessages} fixed — {@code SnapshotTipCheck} and the cleanup search match it
 * verbatim, so nothing may be appended to it — and {@code git} already treats a trailing {@code
 * Key: value} block as structured data, so {@code %(trailers)} and human readers agree.
 *
 * <p>Stamping is optional by design: {@link #stamp} with no epoch returns the message unchanged.
 * That is the pre-contract tip and the claimless writer — both legal, and both carrying no
 * provenance rather than a suspect one.
 *
 * <p>Implements FR13 of harden-task-branch-contract; FR3 of fix-claim-epoch-fence.
 */
public final class ClaimEpochTrailer {

    /** The trailer key; namespaced so a project's own trailers never collide with the factory's. */
    static final String KEY = "Gnomish-Claim-Epoch";

    private static final String PREFIX = KEY + ": ";

    private ClaimEpochTrailer() {}

    /**
     * Appends the epoch trailer to a commit message.
     *
     * @param message the service commit message; never null
     * @param epoch the tenure's epoch, or {@code null} when the writer holds no claim
     * @return the message with its trailer, or {@code message} unchanged when there is no epoch
     */
    public static String stamp(String message, @Nullable ClaimEpoch epoch) {
        return epoch == null ? message : message + "\n\n" + PREFIX + epoch.token();
    }
}
