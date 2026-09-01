package com.github.oinsio.gnomish.app.sandboxlifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * The structured SLF4J verdict sink `run`/`take` use (NFR-O4 of add-serve-sandbox-lifecycle):
 * every field of {@link SweepVerdict} on one line, in the identical vocabulary the daemon's
 * ledger later renders (design D6) — "Same vocabulary in daemon and one-shot logs".
 *
 * <p>The line's <em>level</em> follows the verdict's category (FR12 of
 * harden-logging-observability, sandbox-lifecycle "Quiet tick, loud degradation"): a sweep over a
 * healthy factory finds mostly living and young objects, and one INFO each would make every tick
 * of a busy instance a wall of text that hides the one object something went wrong with. So
 * steady-state verdicts are DEBUG, the verdicts that <em>did</em> something are INFO, and a
 * {@link SweepVerdictCategory#SKIPPED_NO_VERDICT} — the sweep failing to reach a verdict at all —
 * is WARN, because it is the one category that leaves work undone.
 *
 * <p>Implements FR12 of harden-logging-observability.
 */
public final class Slf4jSweepVerdictListener implements SweepVerdictListener {

    private static final Logger log = LoggerFactory.getLogger("gnomish.sandbox.lifecycle");

    @Override
    public void onVerdict(SweepVerdict verdict) {
        log.atLevel(levelOf(verdict.category()))
                .log(
                        "sweep {} object={} role={} mode={} task={} reason=\"{}\" age={}",
                        verdict.category(),
                        verdict.objectName(),
                        verdict.role(),
                        verdict.mode(),
                        verdict.taskKey(),
                        verdict.reason(),
                        verdict.age());
    }

    /**
     * The category-to-level grading. Exhaustive over the enum with no {@code default}, so a new
     * category cannot be added without deciding what an operator should do about it.
     */
    private static Level levelOf(SweepVerdictCategory category) {
        return switch (category) {
            case CHECKED_ALIVE, KEPT_UNDER_THRESHOLD -> Level.DEBUG;
            case STOPPED_ORPHAN, DISPOSED_AGED, DISPOSED_RECONSTRUCTIBLE -> Level.INFO;
            case SKIPPED_NO_VERDICT -> Level.WARN;
        };
    }
}
