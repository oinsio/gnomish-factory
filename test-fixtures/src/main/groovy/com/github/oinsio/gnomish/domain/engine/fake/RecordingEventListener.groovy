package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener

/**
 * A recording {@link EngineEventListener}: appends every {@link EngineEvent} to the
 * public {@link #events} list so a spec can assert the emitted stream. With
 * {@link #throwOnEvent} set it throws a {@link RuntimeException} on every
 * {@code onEvent} (after recording it), driving the section-6 broken-listener spec
 * that proves the engine swallows a listener failure.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated.
 */
class RecordingEventListener implements EngineEventListener {

    /** Every event received, in emission order. */
    final List<EngineEvent> events = []

    /** When true, {@code onEvent} throws after recording the event. */
    boolean throwOnEvent = false

    @Override
    void onEvent(EngineEvent event) {
        events << event
        if (throwOnEvent) {
            throw new RuntimeException('broken listener on ' + event.getClass().simpleName)
        }
    }
}
