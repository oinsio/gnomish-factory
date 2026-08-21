package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's per-category sweep counts, shared by {@code vitals.sweep.counts} and the
 * ledger's {@code sweepTick.counts} (NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle).
 *
 * @param checkedAlive objects left untouched (fresh claim, or under the minimum object age)
 * @param keptUnderThreshold unowned stopped boxes and remnants still under the reap threshold
 * @param stoppedOrphan running boxes stopped because they were unowned
 * @param disposedAged kept environments and remnants disposed past the reap threshold
 * @param disposedReconstructible guard/judge/verification/seed-helper objects disposed at once
 * @param skippedNoVerdict tracked objects no verdict could be reached for
 */
public record SweepCountsDto(
        int checkedAlive,
        int keptUnderThreshold,
        int stoppedOrphan,
        int disposedAged,
        int disposedReconstructible,
        int skippedNoVerdict) {}
