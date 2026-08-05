package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.LedgerLifecycleEvent
import com.github.oinsio.gnomish.serveobservability.LifecycleLine
import java.time.Instant

/**
 * Shared test fixture for building a {@link LifecycleLine} with a fixed instance and
 * timestamp, so appender specs only need to vary the lifecycle reason.
 *
 * <p>Implements FR14 of add-serve-observability.
 */
trait LifecycleLineFixture {

    LifecycleLine lifecycleLine(String reason) {
        def instance = new InstanceInfo('instance-1', 'host-1', '1.0.0')
        LedgerLifecycleEvent event = reason == 'started'
                ? new LedgerLifecycleEvent.Started()
                : new LedgerLifecycleEvent.Stopped(reason)
        return new LifecycleLine(instance, Instant.parse('2026-08-03T10:00:00Z'), event)
    }
}
