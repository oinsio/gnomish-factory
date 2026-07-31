package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.time.Duration
import spock.lang.Specification

/**
 * TakeHeartbeat.forRun assembles the per-{@code take}-run heartbeat views (task 6.1, design D3, D4).
 * The default 3-argument overload supplies the production {@code SystemMonotonicTime}; this spec
 * drives that overload so the whole assembly is exercised and a non-null bundle of the three wired
 * views (the beat lifecycle, the progress listener, the claim-loss flag) is returned.
 *
 * FR1, FR4, FR8 of add-claim-heartbeat.
 */
class TakeHeartbeatSpec extends Specification {

    // FR1, FR4, FR8: the default overload assembles a non-null bundle of the three heartbeat views.
    def "forRun assembles the beat, progress and claim-loss views from the tracker config"() {
        given:
        def tracker = Mock(Tracker)
        def config = new TrackerConfig('fixture', 3)
        def sleeper = { Duration d -> } as Sleeper

        when:
        def heartbeat = TakeHeartbeat.forRun(tracker, config, sleeper)

        then:
        heartbeat != null
        heartbeat.instance() != null
        heartbeat.progress() != null
        heartbeat.flag() != null
    }
}
