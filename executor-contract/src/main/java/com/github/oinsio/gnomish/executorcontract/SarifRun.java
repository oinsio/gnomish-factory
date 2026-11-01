package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * One run of a verdict's SARIF log: the name of the tool that produced it ({@code
 * tool.driver.name}) and its results. The tool name is the executor's text, a carrier (NFR-S2).
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract.
 */
public record SarifRun(UntrustedText tool, List<SarifResult> results) {

    /** Copies the results. */
    public SarifRun {
        results = List.copyOf(results);
    }
}
