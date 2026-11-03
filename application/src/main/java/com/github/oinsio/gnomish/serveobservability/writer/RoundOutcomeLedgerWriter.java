package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.RoundOutcomeLine;
import com.github.oinsio.gnomish.status.RoundOutcomeWords;
import java.io.IOException;
import java.time.InstantSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The serve daemon's write point for the ledger's {@code roundOutcome} line (NFR-O2, UX3 of
 * define-executor-contract): an engine event listener that appends one line per {@link
 * EngineEvent.ExecutionFinished} — the event every executed round emits — through the shared
 * {@link RotatingLedgerAppender}, in the words {@link RoundOutcomeWords} owns. Every other event is
 * not this writer's business. One instance serves every slot of the daemon: it holds no per-run
 * state, and the appender serializes the writes.
 *
 * <p>Write failures never propagate (NFR-R1 of add-serve-observability): an {@link IOException}
 * from the appender is logged and swallowed, as for every ledger line — an observability write must
 * never fail a round.
 *
 * <p>Implements NFR-O2, UX3 of define-executor-contract.
 */
public final class RoundOutcomeLedgerWriter implements EngineEventListener {

    private static final Logger log = LoggerFactory.getLogger(RoundOutcomeLedgerWriter.class);

    private final RotatingLedgerAppender appender;
    private final InstanceInfo instance;
    private final InstantSource clock;

    /**
     * @param appender the shared ledger append point every line is written through; never null
     * @param instance this factory instance's identity, carried on every written line; never null
     * @param clock supplies each line's {@code at}; never null
     */
    public RoundOutcomeLedgerWriter(RotatingLedgerAppender appender, InstanceInfo instance, InstantSource clock) {
        this.appender = appender;
        this.instance = instance;
        this.clock = clock;
    }

    /**
     * Appends the {@code roundOutcome} line of a finished round; ignores every other event.
     *
     * @param event the event that just occurred; never null
     */
    @Override
    public void onEvent(EngineEvent event) {
        if (!(event instanceof EngineEvent.ExecutionFinished finished)) {
            return;
        }
        var key = finished.key();
        var line = new RoundOutcomeLine(
                instance,
                clock.instant(),
                key.taskId(),
                key.stage(),
                key.attempt(),
                finished.executor().name(),
                finished.executor().version(),
                RoundOutcomeWords.STAGE_ROLE,
                RoundOutcomeWords.statusWithClass(finished.status()));
        try {
            appender.append(line);
        } catch (IOException e) {
            log.error(
                    OperatorEvent.ROUND_OUTCOME_LEDGER_APPEND_FAILED.head()
                            + "failed to append roundOutcome ledger line for task {}",
                    key.taskId(),
                    e);
        }
    }
}
