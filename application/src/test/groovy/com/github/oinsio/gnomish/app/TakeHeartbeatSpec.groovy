package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.BlockingSleeper
import com.github.oinsio.gnomish.app.lease.HeartbeatStateListener
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * TakeHeartbeat.forRun assembles the per-{@code take}-run heartbeat views (task 6.1, design D3, D4).
 * The default 3-argument overload supplies the production {@code SystemMonotonicTime}; this spec
 * drives that overload so the whole assembly is exercised and a non-null bundle of the three wired
 * views (the beat lifecycle, the progress listener, the claim-loss flag) is returned. The serve
 * overload additionally threads a {@link HeartbeatStateListener} into the {@code InstanceHeartbeat}
 * (add-serve-observability FR1, FR7).
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

    // FR1, FR7 of add-serve-observability: the serve overload wires the state listener into the
    //     InstanceHeartbeat, so the worker-start transition driven through the assembled beat
    //     lifecycle fires it — proving the listener is threaded through, not dropped.
    @Timeout(10)
    def "the serve overload threads the state listener into the assembled heartbeat"() {
        given:
        def tracker = Stub(Tracker)
        def config = new TrackerConfig('fixture', 3)
        def sleeper = new BlockingSleeper()
        def fired = new AtomicInteger()
        HeartbeatStateListener listener = { -> fired.incrementAndGet() }

        when: 'a claim registers through the assembled beat lifecycle, starting the worker'
        def heartbeat = TakeHeartbeat.forRun(tracker, config, sleeper, listener)
        heartbeat.instance().register(new TaskRef('github:o/r#1'))
        sleeper.awaitEntered()

        then: 'the worker-start transition fired the threaded listener'
        fired.get() == 1

        cleanup:
        heartbeat.instance().unregister(new TaskRef('github:o/r#1'))
        sleeper.releaseOne()
    }
}
