package com.github.oinsio.gnomish.app.sandboxlifecycle;

import java.util.EnumMap;
import java.util.Map;

/**
 * Wraps another {@link SweepVerdictListener}, tallying per-category counts so a one-shot entry
 * point (`take`, NFR-O4 of add-serve-sandbox-lifecycle) can print a one-line finish-report summary
 * after its startup sweep pass, without a daemon-only ledger.
 */
public final class SweepSummaryListener implements SweepVerdictListener {

    private final SweepVerdictListener delegate;
    private final Map<SweepVerdictCategory, Integer> counts = new EnumMap<>(SweepVerdictCategory.class);

    public SweepSummaryListener(SweepVerdictListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onVerdict(SweepVerdict verdict) {
        delegate.onVerdict(verdict);
        counts.merge(verdict.category(), 1, Integer::sum);
    }

    /**
     * Renders the tallied counts as one line.
     *
     * @return a one-line summary, e.g. {@code "sweep: 2 checked-alive, 1 disposed-aged"}; {@code
     *     "sweep: nothing to report"} when no object was evaluated
     */
    public String summaryLine() {
        if (counts.isEmpty()) {
            return "sweep: nothing to report";
        }
        StringBuilder line = new StringBuilder("sweep: ");
        boolean first = true;
        for (var entry : counts.entrySet()) {
            if (!first) {
                line.append(", ");
            }
            line.append(entry.getValue()).append(' ').append(categoryLabel(entry.getKey()));
            first = false;
        }
        return line.toString();
    }

    private static String categoryLabel(SweepVerdictCategory category) {
        return category.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
