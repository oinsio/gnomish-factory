package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener

/**
 * The recording
 * {@link com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener} is the
 * first concrete implementation of {@link EngineEventListenerContract}: it appends
 * every delivered {@link EngineEvent} to its public {@code events} list in order, so
 * no contract row is skipped. The status-snapshot (§6.2), MDC (§8.2) and logging
 * (§8.3) listeners later subclass the SAME suite through the observation hook.
 *
 * <p>FR14 of add-manual-run: the recording fake passes the extracted port-contract
 * suite unchanged (metric M2).
 */
class RecordingEventListenerContractSpec extends EngineEventListenerContract {

    @Override
    protected Optional<?> arrange() {
        Optional.of(new RecordingEventListener())
    }

    @Override
    protected List<EngineEvent> observedEvents(Object adapter) {
        ((RecordingEventListener) adapter).events
    }
}
