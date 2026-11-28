package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The judgement of a {@code streams_progress} program's stdout lines (FR5 of define-executor-contract;
 * design D3), the {@link ProgressLines.Sink} its live reader hands each line to: each line is NDJSON
 * progress, logged at DEBUG and never parsed for a result; a line that is not JSON is a contract
 * violation, logged and skipped — the first of a run at WARN, the rest at DEBUG and counted, so a
 * chatty executor does not flood the operator plane. A cut line that no longer parses is reported as
 * cut, not as one that is not JSON: the cause is its length.
 *
 * <p>One judge serves one run, on its reader's thread only.
 *
 * <p>Implements FR5, NFR-O2 of define-executor-contract.
 */
final class ProgressJudge implements ProgressLines.Sink {

    /** The most bytes of one progress line kept. */
    static final int LINE_CAP_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ProgressJudge.class);

    private final String executor;
    private int violations;

    /** @param executor the executor's name, for the log lines; never blank */
    ProgressJudge(String executor) {
        this.executor = executor;
    }

    @Override
    public void line(byte[] bytes, boolean cut) {
        UntrustedText line = UntrustedText.subprocess(new String(bytes, StandardCharsets.UTF_8));
        if (line.isBlank()) {
            return;
        }
        try {
            ExecutorContractJson.reader().readTree(bytes);
            log.debug("program executor '{}' progress: {}", executor, line.forLog());
        } catch (IOException notJson) {
            violations++;
            if (violations == 1) {
                log.warn(
                        OperatorEvent.PROGRAM_PROGRESS_NOT_JSON.head()
                                + "program executor '{}' wrote a progress line {}; skipped: {}",
                        executor,
                        cut
                                ? "over " + LINE_CAP_BYTES / 1024 + " KiB that was cut and no longer parses"
                                : "that is not JSON, a contract violation",
                        line.forLog());
            } else if (cut) {
                log.debug(
                        "program executor '{}' wrote another progress line over {} KiB that was cut; skipped: {}",
                        executor,
                        LINE_CAP_BYTES / 1024,
                        line.forLog());
            } else {
                log.debug("program executor '{}' wrote another non-JSON progress line: {}", executor, line.forLog());
            }
        }
    }

    @Override
    public void ended() {
        if (violations > 1) {
            log.debug("program executor '{}' wrote {} non-JSON progress lines", executor, violations);
        }
    }
}
