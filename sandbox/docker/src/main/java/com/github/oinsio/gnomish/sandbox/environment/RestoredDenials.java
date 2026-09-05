package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import com.github.oinsio.gnomish.sandbox.DenialRestoration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a resume brought about denials already reported, and how one guard consumes it (FR4, FR5,
 * FR7, FR8 of fix-denial-attribution-durability): the offered read position, the identities the
 * branch records, and the loss the offer itself can reveal.
 *
 * <p>Its own class because it is a decision, not a pair of fields, and the decision is the one the
 * whole change turns on: an offered position is applied only when it names the live denial source,
 * because a position stamped by another machine's daemon — or by a container since recreated —
 * would filter real denials out of the report instead. When it does not apply, the read falls back
 * to the source's whole tail (design D3) and the recorded identities turn that fallback into a
 * merge rather than a duplicated report. Keeping all three together is what stops "position
 * rejected" and "identities available" from drifting apart; {@link GuardDenialReads} is then about
 * reading.
 *
 * <p>The one loss visible from here is a committed position naming a source that is gone while the
 * branch records denials that source produced: its log went with it, so what it held past the last
 * committed record can never be read by anyone. That is reported in-band as a synthetic denial
 * (FR8, design D6) rather than as a WARN no reader of the task report will ever see.
 */
final class RestoredDenials {

    private static final Logger log = LoggerFactory.getLogger(RestoredDenials.class);

    private final String key;
    private final RecordedDenialMerge merge;

    /** A cursor committed by an earlier lease, awaiting the source match of the first read (FR5). */
    private @Nullable DenialCursor offered;

    /** A loss the offer revealed, owed to the next read's denials and reported exactly once (FR8). */
    private @Nullable Denial pendingLoss;

    RestoredDenials(String key) {
        this.key = key;
        this.merge = new RecordedDenialMerge(key);
    }

    /** Accepts what an earlier lease recorded; consumed at the first read that follows. */
    void restore(DenialRestoration restoration) {
        offered = restoration.position().orElse(null);
        merge.restore(restoration.recorded());
    }

    /**
     * Consumes the offered position once, before a read: the position to start that read from, or
     * {@code null} to keep the current one — which for a rejected offer means reading the live
     * source from its start, correct because that source's log holds no round the factory already
     * reported.
     *
     * @param liveSource the live denial source's identity, asked for only when an offer exists so
     *     an unrestored guard never probes the daemon for it
     * @return the position to read from, or null to leave the current position alone
     */
    @Nullable
    String positionFor(Supplier<@Nullable String> liveSource) {
        DenialCursor cursor = offered;
        if (cursor == null) {
            return null;
        }
        offered = null;
        String source = liveSource.get();
        if (cursor.source().equals(source)) {
            return cursor.position();
        }
        log.info(
                "committed denial cursor for {} was read from guard container {}, not the live {} —"
                        + " reading its log from the start (FR5)",
                key,
                cursor.source(),
                source == null ? "(unreadable)" : source);
        // Only where the branch actually records denials of that source: with nothing recorded
        // there is nothing the dead source can have lost, and a marker would make a quiet task
        // look damaged.
        if (merge.recordsAny()) {
            pendingLoss = DenialLossMarker.sourceGone(key, cursor.source(), source);
        }
        return null;
    }

    /**
     * The loss markers owed to this read, as the list its denials are collected into. Owed once: a
     * loss seen while restoring is reported with the first read that follows it and never again,
     * so a task that keeps running does not accumulate the same marker per round.
     */
    List<Denial> owedLoss() {
        List<Denial> denials = new ArrayList<>();
        Denial loss = pendingLoss;
        if (loss != null) {
            pendingLoss = null;
            denials.add(loss);
        }
        return denials;
    }

    /** {@code read} without the denials the branch already records (FR7). */
    List<Denial> merge(List<Denial> read) {
        return merge.merge(read);
    }
}
