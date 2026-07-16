package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers an {@link EngineEvent} to a run's {@link EngineEventListener}, swallowing and
 * logging any {@link RuntimeException} the listener throws so a broken listener never breaks
 * a run: the event stream is observability, never an effect (design D7, FR12). Every capture
 * point that emits an event — {@link Engine} (the run bookends), {@link RoundExecution} (the
 * per-round {@code ExecutionFinished}), {@link VerifyOrchestrator} (the per-check events) and
 * {@link AttemptJournal} (the attempt events) — routes through this one helper rather than
 * duplicating the swallow-and-log plumbing, mirroring {@link StackTraces}.
 *
 * <p>A stateless, package-private static-only utility with no instances (NFR-R1); the WARN
 * log is emitted here, at the point of capture (NFR-O1).
 *
 * <p>Implements FR12, NFR-O1 of add-stage-engine.
 */
final class Events {

    private static final Logger log = LoggerFactory.getLogger(Events.class);

    /**
     * Never instantiated: this is a static-only utility. Kept as an explicit private
     * constructor that throws so the no-instances contract is enforced and PIT does not
     * flag an unreachable default constructor as a surviving mutation.
     */
    private Events() {
        throw new AssertionError("no instances");
    }

    /**
     * Delivers {@code event} to {@code listener}, swallowing and logging any {@link
     * RuntimeException} it throws at WARN at the point of capture (design D7, NFR-O1). The
     * listener is observability: its failure is recorded but never propagated, so a broken
     * listener leaves the run's control flow untouched (FR12).
     *
     * <p>Implements FR12, NFR-O1 of add-stage-engine.
     *
     * @param listener the run's event listener the event is delivered to; never null
     * @param event the event to deliver; never null
     */
    static void emit(EngineEventListener listener, EngineEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException ex) {
            log.warn("event listener threw for {}", event, ex);
        }
    }
}
