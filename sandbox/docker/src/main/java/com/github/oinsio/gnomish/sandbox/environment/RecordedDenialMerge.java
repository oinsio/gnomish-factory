package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.DenialIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges a read against the denials the task branch already records (FR7, design D5 of
 * fix-denial-attribution-durability): a denial whose identity is already recorded is dropped,
 * every other one is attached.
 *
 * <p>This is what makes losing the read position cheap. Without it, an unreadable, absent, or
 * foreign position sends the read back over the source's whole tail and the report doubles every
 * denial the branch already carries (design D3 — duplicates, deliberately, over silence). With
 * it, the same fallback recovers exactly the unrecorded events, and the merge says so in one
 * line so a reviewer can tell a recovered tail from a quiet one.
 *
 * <p>Identity comes from the source's event coordinates, never from the finding's content: two
 * denials to the same host, path, and method are two events, and repeats are the signal. A denial
 * with no identity — a loss marker, a line the daemon did not stamp, a record written before
 * identities existed — matches nothing and is always attached ("unknown, keep").
 *
 * <p>Implements FR7, NFR-R3 of fix-denial-attribution-durability.
 */
final class RecordedDenialMerge {

    private static final Logger log = LoggerFactory.getLogger(RecordedDenialMerge.class);

    private final String key;

    /** The identities the branch tip already records; empty until a resume offers them. */
    private Set<DenialIdentity> recorded = Set.of();

    RecordedDenialMerge(String key) {
        this.key = key;
    }

    /** Accepts the identities recorded at the branch tip; every later read merges against them. */
    void restore(Set<DenialIdentity> identities) {
        recorded = identities;
    }

    /**
     * Whether the branch records any denial of its own. It is what separates "this task never
     * recorded a denial" from "this task recorded denials whose source is gone" — the second is a
     * loss worth a marker (FR8, design D6), the first is a quiet task that must show nothing.
     */
    boolean recordsAny() {
        return !recorded.isEmpty();
    }

    /**
     * {@code read} without the denials the branch already records, in read order.
     *
     * @param read the denials this read returned; never null
     * @return the denials not yet recorded against this task; never null
     */
    List<Denial> merge(List<Denial> read) {
        List<Denial> recovered = new ArrayList<>();
        for (Denial denial : read) {
            DenialIdentity identity = denial.identity();
            if (identity == null || !recorded.contains(identity)) {
                recovered.add(denial);
            }
        }
        int alreadyPresent = read.size() - recovered.size();
        if (alreadyPresent > 0) {
            log.info(
                    "re-read of the egress denial log for {}: {} already present, {} recovered",
                    key,
                    alreadyPresent,
                    recovered.size());
        }
        return List.copyOf(recovered);
    }
}
