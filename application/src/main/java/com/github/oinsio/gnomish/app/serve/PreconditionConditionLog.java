package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lines of an operator precondition's {@link DaemonCondition} (design D19 of
 * add-sandbox-base-image, NFR-O2): its own code per kind on the opening WARN and the sustained-open
 * ERROR, so the condition is named by the code, and the cause is the check's own message, which
 * carries the fix. The checks themselves log nothing; this is the line a refusal under {@code
 * serve} writes, once per episode, however many slots meet it.
 *
 * <p>Implements FR15, NFR-O2, UX4 of add-sandbox-base-image.
 *
 * @param kind the condition's precondition, never {@link DaemonConditionKind#REMOTE}
 */
record PreconditionConditionLog(DaemonConditionKind kind) implements ConditionLog {

    private static final Logger log = LoggerFactory.getLogger(DaemonCondition.class);

    private static final String OPENED = "daemon condition {} opened: no task is claimed until it closes: {}";
    private static final String SUSTAINED = "daemon condition {} has been open longer than {}: still {}";

    PreconditionConditionLog {
        if (kind == DaemonConditionKind.REMOTE) {
            throw new IllegalArgumentException("the remote writes the remote outage gate's lines");
        }
    }

    @Override
    public void opened(String reason) {
        switch (kind) {
            case CONTAINER_RUNTIME ->
                log.warn(OperatorEvent.CONDITION_CONTAINER_RUNTIME_OPENED.head() + OPENED, kind, reason);
            case CLONE_REF_FORMAT ->
                log.warn(OperatorEvent.CONDITION_CLONE_REF_FORMAT_OPENED.head() + OPENED, kind, reason);
            case IMAGE_CONTRACT ->
                log.warn(OperatorEvent.CONDITION_IMAGE_CONTRACT_OPENED.head() + OPENED, kind, reason);
            case BOX_GIT_FLOOR -> log.warn(OperatorEvent.CONDITION_BOX_GIT_FLOOR_OPENED.head() + OPENED, kind, reason);
            case REMOTE -> {
                // refused by the compact constructor
            }
        }
    }

    @Override
    public void probeFailed(RepeatOccurrence occurrence) {
        switch (occurrence) {
            case RepeatOccurrence.First first -> log.debug("daemon condition {} still unmet: {}", kind, first.reason());
            case RepeatOccurrence.Repeat repeat ->
                log.debug("daemon condition {} still unmet ({}x): {}", kind, repeat.count(), repeat.reason());
            case RepeatOccurrence.RollUp rollUp ->
                log.debug(
                        "daemon condition {} still unmet {}x over {}: {}",
                        kind,
                        rollUp.count(),
                        rollUp.elapsed(),
                        rollUp.reason());
        }
    }

    @Override
    public void probeUnanswered(RepeatOccurrence occurrence, RuntimeException cause) {
        if (occurrence instanceof RepeatOccurrence.First first) {
            log.debug("daemon condition {} still unmet: {}", kind, first.reason(), cause);
            return;
        }
        probeFailed(occurrence);
    }

    @Override
    public void sustainedOpen(Duration threshold, String lastError) {
        switch (kind) {
            case CONTAINER_RUNTIME ->
                log.error(
                        OperatorEvent.CONDITION_CONTAINER_RUNTIME_SUSTAINED_OPEN.head() + SUSTAINED,
                        kind,
                        threshold,
                        lastError);
            case CLONE_REF_FORMAT ->
                log.error(
                        OperatorEvent.CONDITION_CLONE_REF_FORMAT_SUSTAINED_OPEN.head() + SUSTAINED,
                        kind,
                        threshold,
                        lastError);
            case IMAGE_CONTRACT ->
                log.error(
                        OperatorEvent.CONDITION_IMAGE_CONTRACT_SUSTAINED_OPEN.head() + SUSTAINED,
                        kind,
                        threshold,
                        lastError);
            case BOX_GIT_FLOOR ->
                log.error(
                        OperatorEvent.CONDITION_BOX_GIT_FLOOR_SUSTAINED_OPEN.head() + SUSTAINED,
                        kind,
                        threshold,
                        lastError);
            case REMOTE -> {
                // refused by the compact constructor
            }
        }
    }

    @Override
    public void closed(Duration episode, int failedProbes) {
        log.info("daemon condition {} closed: open {} over {} failed probe(s)", kind, episode, failedProbes);
    }
}
