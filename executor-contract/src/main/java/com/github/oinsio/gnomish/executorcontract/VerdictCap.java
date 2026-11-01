package com.github.oinsio.gnomish.executorcontract;

import java.util.List;

/**
 * The cap on the results one {@link Answer.Verdict} carries, over all its runs. A log above the
 * cap is refused whole rather than cut: a cut could drop the one result that fails the threshold.
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract.
 */
final class VerdictCap {

    private VerdictCap() {}

    static void check(List<SarifRun> runs) {
        int count = 0;
        for (SarifRun run : runs) {
            count += run.results().size();
        }
        if (count > Answer.Verdict.MAX_RESULTS) {
            throw new IllegalArgumentException("the verdict carries " + count + " SARIF results; at most "
                    + Answer.Verdict.MAX_RESULTS + " are read");
        }
    }
}
