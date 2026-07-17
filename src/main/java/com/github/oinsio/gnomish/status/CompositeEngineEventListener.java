package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import java.util.List;

/**
 * Fans one {@link EngineEvent} out to every listener in a fixed list, so the wiring that
 * assembles {@link com.github.oinsio.gnomish.domain.engine.EnginePorts} can register several
 * cross-cutting listeners — status snapshot (this change), MDC and structured logging (section 8
 * of add-manual-run) — behind the single {@link EngineEventListener} slot {@code EnginePorts}
 * exposes (design D10).
 *
 * <p>Delegates each call in list order with no exception handling of its own: the engine's own
 * {@code Events.emit} helper already wraps the single call it makes to this composite in a
 * try/catch that logs and swallows a {@link RuntimeException} (design D7 of add-stage-engine), so
 * one listener in the list throwing would otherwise abort delivery to the listeners after it in
 * the same fan-out — deliberately out of scope here: the risk is accepted the same way a single
 * listener's own failure is accepted upstream, and duplicating a swallow-per-call here would mask
 * which listener actually threw.
 *
 * <p>Implements D10 of add-manual-run.
 *
 * @param listeners the listeners to deliver every event to, in order; defensively copied
 */
public record CompositeEngineEventListener(List<EngineEventListener> listeners) implements EngineEventListener {

    public CompositeEngineEventListener {
        listeners = List.copyOf(listeners);
    }

    @Override
    public void onEvent(EngineEvent event) {
        for (EngineEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}
