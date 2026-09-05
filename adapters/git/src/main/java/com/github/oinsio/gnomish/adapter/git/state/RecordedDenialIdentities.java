package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.DenialIdentity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The identities of the denials a branch tip already records — across {@code state.json}'s
 * attempts and {@code task.json}'s {@code cannotExecute} escalation (FR7 of
 * fix-denial-attribution-durability).
 *
 * <p>What a resuming instance hands its environment beside the committed read position. The
 * position is the fast path; this set is the correctness path: when the position is absent or
 * names a source the box no longer runs, the read falls back to the source's whole tail (design
 * D3) and these identities turn what would be a duplicated report into a clean merge.
 *
 * <p>Entries with no identity — documents written before the field existed, and loss markers —
 * contribute nothing, so they keep the pre-change behaviour of being re-read and re-attached.
 *
 * <p>Implements FR7 of fix-denial-attribution-durability.
 */
public final class RecordedDenialIdentities {

    private RecordedDenialIdentities() {}

    /**
     * Every denial identity the two envelopes of one tip record.
     *
     * @param state the tip's {@code state.json}, or {@code null} when it carries none
     * @param task the tip's {@code task.json}, or {@code null} when it carries none
     * @return the recorded identities; never null, possibly empty
     */
    public static Set<DenialIdentity> of(@Nullable StateJsonDto state, @Nullable TaskJsonDto task) {
        Set<DenialIdentity> identities = new LinkedHashSet<>();
        if (state != null) {
            state.attempts().forEach(attempt -> collect(attempt.denials(), identities));
        }
        if (task != null && task.lastEscalation() instanceof EscalationReportDto.CannotExecute escalation) {
            collect(escalation.denials(), identities);
        }
        return Set.copyOf(identities);
    }

    private static void collect(List<StateDenialDto> denials, Set<DenialIdentity> into) {
        for (StateDenialDto denial : denials) {
            DenialIdentityDto identity = denial.identity();
            if (identity != null) {
                into.add(new DenialIdentity(identity.source(), identity.at()));
            }
        }
    }
}
