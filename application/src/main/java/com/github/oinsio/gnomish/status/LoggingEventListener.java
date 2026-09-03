package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.logtext.LogText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link EngineEventListener} adapter that logs one structured INFO line per {@link
 * EngineEvent} to the rolling file appender (task 8.1), so a "days-horizon post-mortem" (design
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
 * <p>Implements NFR-O2 of add-manual-run.
 */
public final class LoggingEventListener implements EngineEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventListener.class);

    /**
     * Logs one structured INFO line for {@code event}, with content specific to its kind, by an
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
            case EngineEvent.ExecutionFinished finished -> logExecutionFinished(finished.usage());
            case EngineEvent.CheckStarted started ->
                log.info("check started: {}", LogText.forLog(started.check().label()));
            case EngineEvent.CheckFinished finished ->
                log.info(
                        "check finished: {} -> {}",
                        LogText.forLog(finished.result().checkRef().label()),
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

    private void logExecutionFinished(ExecutorUsage usage) {
        log.info("execution finished: wallTime={}, tokensByModel={}", usage.wallTime(), usage.tokensByModel());
    }
}
