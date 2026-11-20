package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.logtext.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link EngineEventListener} adapter that logs one structured line per {@link EngineEvent} to
 * the rolling file appender (task 8.1) — INFO for every event but a pending check's poll, which is
 * DEBUG since it is emitted once per poll of a loop (the level table of the logging rule; NFR-O1 of
 * migrate-verify-to-executor-contract), so a "days-horizon post-mortem" (design
 * D9's stated audience) can reconstruct a run's shape straight from the log file without replaying
 * the JSON status contract. Every line is logged through SLF4J with parameterized arguments — never
 * string concatenation — matching the established idiom in {@code
 * com.github.oinsio.gnomish.domain.engine.Events} and {@code
 * com.github.oinsio.gnomish.app.ManualRunRunner}.
 *
 * <p>Because the console appender is WARN+ only (task 8.1), every line this listener logs at INFO
 * lands in the file alone and never reaches the operator's console dialog — exactly what NFR-O2's
 * "stdout belongs to the dialog" requires.
 *
 * <p>The {@code taskId}/{@code stage}/{@code attempt} MDC keys are already set by the time this
 * listener runs (task 8.2, {@link MdcEventListener}), so a line's own message deliberately does not
 * repeat them; each message instead carries the payload specific to its event kind, kept to a
 * short, genuinely useful summary rather than a dump of the full record (e.g. {@code
 * AttemptFinished} logs {@code newState.attemptsUsed()}, not the whole {@code TaskState}).
 *
 * <p>A check's {@code label} is not the factory's own text: {@link
 * com.github.oinsio.gnomish.domain.engine.CheckRef#of} derives it from the target repository's
 * {@code .gnomish/} manifest — {@code command:<command>}, {@code judge:<criteriaFile>} — which a
 * gnome or an untrusted pull request writes. It therefore reaches a line only through {@link
 * LogText}, exactly as {@code ShellCommandCheckRunner} sanitizes the same string for its own
 * identity (FR6 of harden-logging-observability).
 *
 * <p>Every branch here is a plain SLF4J logging call with no I/O beyond the logging framework
 * itself, so — like {@link MdcEventListener} and {@link StatusEventListener} — this class needs no
 * defensive exception handling to satisfy the port's "never throw past {@code onEvent}" contract.
 *
 * <p>Implements NFR-O2 of add-manual-run; NFR-O2, UX3 of define-executor-contract; NFR-O1, UX1 of
 * migrate-verify-to-executor-contract.
 */
public final class LoggingEventListener implements EngineEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventListener.class);

    /** How the round line spells a value the round has none of. */
    private static final String ABSENT = "-";

    /**
     * Logs one structured line for {@code event} (DEBUG for a poll, INFO otherwise), with content specific to its kind, by an
     * exhaustive switch over the sealed {@link EngineEvent} variants — no {@code default} arm, so a
     * new variant fails to compile here until its log line is added.
     *
     * <p>Implements NFR-O2 of add-manual-run.
     *
     * @param event the event that just occurred; never null
     */
    @Override
    public void onEvent(EngineEvent event) {
        switch (event) {
            case EngineEvent.RunStarted started ->
                log.info("run started: position={}, attemptsUsed={}", started.position(), started.attemptsUsed());
            case EngineEvent.AttemptStarted started -> log.info("attempt started: {}", started.key());
            case EngineEvent.ExecutionFinished finished -> logExecutionFinished(finished);
            case EngineEvent.CheckStarted started ->
                log.info("check started: {}", started.check().label().forLog());
            case EngineEvent.CheckPolled polled ->
                log.debug(
                        "pending poll: executor={}, key={}, turn={}, elapsed={}, remaining={}",
                        polled.executor(),
                        polled.keyPrefix().forLog(),
                        polled.turn(),
                        polled.elapsed(),
                        polled.remaining());
            case EngineEvent.CheckTimedOut timedOut -> logTimedOut(timedOut);
            case EngineEvent.CheckFinished finished ->
                log.info(
                        "check finished: {} -> {}",
                        finished.result().checkRef().label().forLog(),
                        finished.result().verdict().getClass().getSimpleName());
            case EngineEvent.AttemptFinished finished ->
                log.info(
                        "attempt finished: attemptsUsed={}", finished.newState().attemptsUsed());
            case EngineEvent.TaskFinished finished ->
                log.info(
                        "task finished: outcome={}",
                        finished.outcome().getClass().getSimpleName());
        }
    }

    /**
     * The round's line (NFR-O2, UX3 of define-executor-contract): the executor, the protocol version
     * it was spoken in, the role, the status, the failure class and the bound it reached, in the
     * words {@link RoundOutcomeWords} owns, beside the round's usage; absent values are {@code -}. The
     * executor's version and a reported bound are its own text and leave through their log exit.
     */
    private void logExecutionFinished(EngineEvent.ExecutionFinished finished) {
        var status = finished.status();
        var bound = RoundOutcomeWords.bound(status);
        log.info(
                "execution finished: executor={}, protocolVersion={}, role={}, status={}, class={}, bound={},"
                        + " wallTime={}, byModel={}",
                RoundOutcomeWords.executorForLog(finished.executor()),
                orDash(RoundOutcomeWords.protocolVersion(finished.executor())),
                RoundOutcomeWords.STAGE_ROLE,
                RoundOutcomeWords.status(status),
                orDash(RoundOutcomeWords.failureClass(status)),
                bound == null ? ABSENT : bound.forLog(),
                finished.usage().wallTime(),
                finished.usage().byModel());
    }

    /**
     * The timeout's line (NFR-O1, UX1 of migrate-verify-to-executor-contract): the executor, the last
     * key's prefix, the elapsed wait, the class the engine resolved the wait to and its details —
     * for an {@code scm-status} check every expected context still absent. INFO, not WARN: a
     * timeout is a verdict the verify chain classifies, and the attempt's own outcome is the line an
     * operator acts on.
     */
    private void logTimedOut(EngineEvent.CheckTimedOut timedOut) {
        var details = timedOut.failure().details();
        log.info(
                "pending poll timed out: executor={}, key={}, elapsed={}, class={}, awaiting={}",
                timedOut.executor(),
                timedOut.keyPrefix().forLog(),
                timedOut.elapsed(),
                timedOut.failure().failureClass(),
                details == null ? ABSENT : details.forLog());
    }

    private static Object orDash(@Nullable Object value) {
        return value == null ? ABSENT : value;
    }
}
