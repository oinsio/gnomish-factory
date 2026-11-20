package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One run of a verdict's SARIF log: the name of the tool that produced it ({@code
 * tool.driver.name}), its results, and the URL of the platform run that produced it ({@code
 * properties.runUrl}, design D23 of migrate-verify-to-executor-contract), which the engine records
 * with a pass. The tool name and the URL are the executor's text, each a carrier (NFR-S2); the URL is
 * shown, never followed.
 *
 * <p>Only the canonical constructor exists: every writer states its URL or {@code null}, so none can
 * drop it by omission (D23).
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract; FR6 of migrate-verify-to-executor-contract.
 */
public record SarifRun(
        UntrustedText tool,
        List<SarifResult> results,
        @Nullable UntrustedText runUrl) {

    /** Copies the results. */
    public SarifRun {
        results = List.copyOf(results);
    }
}
