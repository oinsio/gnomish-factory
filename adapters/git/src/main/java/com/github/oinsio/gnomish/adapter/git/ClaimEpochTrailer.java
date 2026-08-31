package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The claim epoch as it lives in a commit message: a git trailer line appended to the service
 * subject, so every commit of a tenure says which tenure made it (design D6, FR13). The trailer is
 * the branch's half of the fence — a reader classifies a tip whose epoch predates the live claim as
 * {@code StaleEpoch} whatever its content says.
 *
 * <p>A trailer rather than a subject suffix, for two reasons: the subject stays the exact string
 * {@link ServiceCommitMessages} fixed — {@code SnapshotTipCheck} and the cleanup search match it
 * verbatim, so nothing may be appended to it — and {@code git} already treats a trailing {@code
 * Key: value} block as structured data, so {@code %(trailers)} and human readers agree.
 *
 * <p>Stamping is optional by design: {@link #stamp} with no epoch returns the message unchanged, and
 * {@link #parse} answers empty for it. That is the pre-contract tip and the claimless writer — both
 * legal, and both simply outside the fence rather than stale.
 *
 * <p>Implements FR13 of harden-task-branch-contract.
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

    /**
     * Reads the epoch out of a commit message.
     *
     * <p>Never throws on content, like every other reader of this contract (NFR-R2): a trailer whose
     * value is not a token this factory can read — a negative number, a word, an empty value — is
     * reported as no epoch at all, which leaves the tip outside the fence rather than corrupt. A
     * message carrying the trailer more than once answers the last one, matching git's own
     * "last trailer wins" reading.
     *
     * @param message the full commit message, subject and body; never null
     * @return the stamped epoch, or empty when the message carries none this factory can read
     */
    public static Optional<ClaimEpoch> parse(String message) {
        ClaimEpoch found = null;
        for (String line : message.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith(PREFIX)) {
                continue;
            }
            ClaimEpoch parsed = epochOf(trimmed.substring(PREFIX.length()).strip());
            if (parsed != null) {
                found = parsed;
            }
        }
        return Optional.ofNullable(found);
    }

    private static @Nullable ClaimEpoch epochOf(String value) {
        try {
            long token = Long.parseLong(value);
            return token < 0 ? null : new ClaimEpoch(token);
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }
}
