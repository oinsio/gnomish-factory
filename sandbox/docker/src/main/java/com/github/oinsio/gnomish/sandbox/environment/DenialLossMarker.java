package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.Finding;
import org.jspecify.annotations.Nullable;

/**
 * The synthetic denials the guard emits about its <em>own</em> losses (FR8, design D6 of
 * fix-denial-attribution-durability). When the factory can see that denials were lost, the loss
 * travels on the same channel the denials would have — a finding in the same list, funnel-fenced
 * like any other — so the report distinguishes "no denials" from "no data" without a second
 * field, a second render path, or a WARN nobody reading the report will see.
 *
 * <p>A marker carries no {@link com.github.oinsio.gnomish.domain.engine.DenialIdentity}: no
 * source stamped it, and it stands for events whose identities are exactly what was lost. It
 * therefore merges away against nothing and is attached to every record that reports the read
 * which produced it — the same duplicate-over-silence stance as design D3.
 *
 * <p>Markers gate nothing, exactly like the denials they stand in for (proposal NG1).
 *
 * <p>Implements FR8, NFR-O3, UX3 of fix-denial-attribution-durability.
 */
final class DenialLossMarker {

    private DenialLossMarker() {}

    /**
     * The read came back filling its {@code --tail} window, so the daemon dropped older lines of
     * that window before anything parsed them — and the position advances past them, making the
     * loss permanent. The window it can bound is "after the previous read position, before the
     * oldest line this read still holds".
     *
     * @param key the environment key whose guard lost the events
     * @param tailLines the tail cap the read asked for
     * @param since the position the read started from, or {@code null} for the container's start
     */
    static Denial tailWindowFull(String key, int tailLines, @Nullable String since) {
        return Denial.unidentified(new Finding(
                "egress denial log truncated: the read filled its " + tailLines
                        + "-line window, so older denials inside it are lost",
                key,
                "loss window: after " + (since == null ? "the guard container's start" : since)
                        + ", before the oldest line this read returned"));
    }

    /**
     * A committed position names a denial source that is no longer the live one, while the branch
     * records denials that source produced: its log is gone with it, so whatever it recorded after
     * the last committed record can never be read. The read falls back to the live source's whole
     * tail, which is correct for the live source and silent about the dead one — this marker is
     * what makes that silence visible.
     *
     * @param key the environment key whose guard was replaced
     * @param recordedSource the source identity the committed position named
     * @param liveSource the live source's identity, or {@code null} when it could not be read
     */
    static Denial sourceGone(String key, String recordedSource, @Nullable String liveSource) {
        return Denial.unidentified(new Finding(
                "egress denials may be lost: the recorded denial source is no longer this box's live guard",
                key,
                "recorded source " + recordedSource + ", live source "
                        + (liveSource == null ? "(unreadable)" : liveSource)));
    }
}
