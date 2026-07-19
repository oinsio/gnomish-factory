package com.github.oinsio.gnomish.adapter.agent;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fans one {@link AgentProgressEvent} out to every listener in a fixed list, so the run
 * assembly can register several subscribers — {@link LoggingAgentProgressListener}, {@link
 * com.github.oinsio.gnomish.status.AgentActivityEnricher} — behind the single {@link
 * AgentProgressListener} slot {@link StreamJsonParser} accepts (design D10, task 9.4).
 *
 * <p>Unlike {@link com.github.oinsio.gnomish.status.CompositeEngineEventListener}, which
 * deliberately relies on its single caller's own outer try/catch ({@code Events.emit}), this
 * composite cannot: {@link AgentProgressEmitter#deliver} wraps only the <em>one</em> call it makes
 * to whichever {@link AgentProgressListener} it was given, so if that one listener is this
 * composite, an unguarded loop would let a throwing child both skip every listener after it in
 * the same fan-out <em>and</em> trip {@code deliver}'s own catch, indistinguishable in the log
 * from a single misbehaving listener. Each child call is therefore individually guarded here —
 * caught, logged, swallowed — so one subscriber's failure never affects the others, matching the
 * per-listener isolation {@link AgentProgressListener}'s contract promises callers.
 *
 * <p>Implements FR7, D10 of add-agent-executor.
 *
 * @param listeners the listeners to deliver every event to, in order; defensively copied
 */
public record CompositeAgentProgressListener(List<AgentProgressListener> listeners) implements AgentProgressListener {

    private static final Logger log = LoggerFactory.getLogger(CompositeAgentProgressListener.class);

    public CompositeAgentProgressListener {
        listeners = List.copyOf(listeners);
    }

    /**
     * Delivers {@code event} to every wrapped listener, in order; a listener that throws is
     * caught, logged at WARN, and skipped — delivery continues to the remaining listeners
     * (design D10).
     *
     * <p>Implements FR7, D10 of add-agent-executor.
     *
     * @param event the progress event that just occurred; never null
     */
    @Override
    public void onProgress(AgentProgressEvent event) {
        for (AgentProgressListener listener : listeners) {
            try {
                listener.onProgress(event);
            } catch (RuntimeException ex) {
                log.warn("agent progress listener threw for {}", event, ex);
            }
        }
    }
}
