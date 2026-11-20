package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * What reading a bare SARIF 2.1.0 log produced ({@link Answer#readSarif}): its runs, read as a
 * verdict answer's {@code sarif} member is but not yet counted against {@link
 * Answer.Verdict#MAX_RESULTS}, or the reason it is malformed. A malformed log is never a pass.
 *
 * <p>Implements FR5 of migrate-verify-to-executor-contract.
 */
public sealed interface SarifLogReading {

    /**
     * The log holds these runs, in the order written.
     *
     * @param runs the runs; defensively copied
     */
    record Parsed(List<SarifRun> runs) implements SarifLogReading {

        /** Copies the runs. */
        public Parsed {
            runs = List.copyOf(runs);
        }
    }

    /** The document is no SARIF log the contract reads; {@code reason} quotes it, so it is a carrier. */
    record Malformed(UntrustedText reason) implements SarifLogReading {}
}
