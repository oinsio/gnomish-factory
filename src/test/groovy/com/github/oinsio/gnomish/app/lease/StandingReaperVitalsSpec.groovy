package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import spock.lang.Specification

/**
 * StandingReaper's vitals reader (task 2.5, add-serve-observability FR7): {@link
 * StandingReaper#lastRunAt}, {@link StandingReaper#restartCount}, and {@link
 * StandingReaper#interval} — the snapshot's {@code vitals.reaper} entry, driven synchronously via
 * {@code tick()}, no thread and no real sleeping.
 *
 * <p>Implements FR7 of add-serve-observability.
 */
class StandingReaperVitalsSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)

    private final VirtualClock clock = new VirtualClock()
    private final StandingReaper reaper =
    new StandingReaper(ReaperDuty.NONE, { Duration d -> } as com.github.oinsio.gnomish.domain.engine.port.Sleeper,
    INTERVAL, { -> [] }, clock)

    // FR7: before any tick, lastRunAt is the construction instant.
    def "lastRunAt starts at construction time"() {
        expect:
        reaper.lastRunAt() == clock.now()
    }

    // FR7: every completed tick stamps lastRunAt from the injected clock.
    def "lastRunAt advances with every completed tick"() {
        when:
        clock.advance(Duration.ofMinutes(5))
        reaper.tick()

        then:
        reaper.lastRunAt() == clock.now()
    }

    // FR7: restartCount starts at zero and never resets — a growing count is reaping
    //     degradation visible as data (design D3), read here without incrementing it.
    def "restartCount starts at zero"() {
        expect:
        reaper.restartCount() == 0
    }

    // FR7: interval() exposes the reaper's own tick cadence — the staleness yardstick carried
    //     into vitals.reaper.intervalSeconds, distinct from the snapshot-write cadence (M1).
    def "interval reports the reaper's tick cadence"() {
        expect:
        reaper.interval() == INTERVAL
    }
}
