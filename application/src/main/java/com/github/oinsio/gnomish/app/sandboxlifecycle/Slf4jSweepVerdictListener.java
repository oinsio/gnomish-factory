package com.github.oinsio.gnomish.app.sandboxlifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The structured SLF4J verdict sink `run`/`take` use (NFR-O4 of add-serve-sandbox-lifecycle):
 * every field of {@link SweepVerdict} on one line, in the identical vocabulary the daemon's
 * ledger later renders (design D6) — "Same vocabulary in daemon and one-shot logs".
 */
public final class Slf4jSweepVerdictListener implements SweepVerdictListener {

    private static final Logger log = LoggerFactory.getLogger("gnomish.sandbox.lifecycle");

    @Override
    public void onVerdict(SweepVerdict verdict) {
        log.info(
                "sweep {} object={} role={} mode={} task={} reason=\"{}\" age={}",
                verdict.category(),
                verdict.objectName(),
                verdict.role(),
                verdict.mode(),
                verdict.taskKey(),
                verdict.reason(),
                verdict.age());
    }
}
