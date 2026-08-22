package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.lease.BlockingSleeper
import com.github.oinsio.gnomish.app.lease.CachedOpenTaskListing
import com.github.oinsio.gnomish.app.lease.LivenessOracle
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.lease.StalenessMemory
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime
import com.github.oinsio.gnomish.domain.engine.port.Clock
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.Timeout

/**
 * {@link SandboxLifecycleTick}, task 4.1 of add-serve-sandbox-lifecycle (design D7): the
 * immediate-then-cadence scheduling and the per-tick call into {@link SandboxLifecyclePass} with
 * a freshly recomputed {@link LivenessOracle#evaluate} verdict.
 */
@Timeout(10)
class SandboxLifecycleTickSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-08-07T12:00:00Z')

    def cloneDir = Path.of('/tmp/project')
    def clock = { -> NOW } as Clock
    def livenessOracle = new LivenessOracle(new CachedOpenTaskListing(), new StalenessMemory(new SystemMonotonicTime(), Duration.ofMinutes(1)))

    def "tick evaluates the liveness oracle fresh and runs the pass against this clone dir"() {
        given:
        def calls = []
        SandboxLifecyclePass pass = { dir, liveness ->
            calls << [dir, liveness]
            ''
        }
        def sandboxTick = new SandboxLifecycleTick(pass, livenessOracle, cloneDir, Duration.ofMinutes(5), Mock(com.github.oinsio.gnomish.domain.engine.port.Sleeper), clock)

        when:
        sandboxTick.tick()

        then:
        calls.size() == 1
        calls[0][0] == cloneDir
        calls[0][1] instanceof LivenessVerdict.NoVerdict
    }

    // The clock must ADVANCE between construction and the tick: lastRunAt is seeded with the
    //     construction instant, so a fixed clock would satisfy this assertion even if tick() never
    //     stamped anything — the tautology this scenario exists to avoid.
    def "tick re-stamps lastRunAt from the clock, replacing the construction instant"() {
        given:
        def instants = [NOW, NOW.plusSeconds(300)].iterator()
        def advancingClock = { -> instants.next() } as Clock
        def sandboxTick = new SandboxLifecycleTick(SandboxLifecyclePass.NONE, livenessOracle, cloneDir, Duration.ofMinutes(5), Mock(com.github.oinsio.gnomish.domain.engine.port.Sleeper), advancingClock)

        expect: 'construction seeds it, so the assertion below cannot pass by accident'
        sandboxTick.lastRunAt() == NOW

        when:
        sandboxTick.tick()

        then:
        sandboxTick.lastRunAt() == NOW.plusSeconds(300)
    }

    def "ticks once at startup, before the first sleep"() {
        given:
        def sleeper = new BlockingSleeper()
        def sandboxTick = new SandboxLifecycleTick(SandboxLifecyclePass.NONE, livenessOracle, cloneDir, Duration.ofMinutes(5), sleeper, clock)

        when:
        sandboxTick.start()
        def slept = sleeper.awaitEntered()

        then:
        slept == Duration.ofMinutes(5)
    }

    // Proves loop() actually calls tick(), not merely reaches the sleep — an observable effect
    // only tick() produces, recorded with no direct tick() call from the test itself.
    def "the startup tick actually runs the pass before the first sleep"() {
        given:
        def sleeper = new BlockingSleeper()
        def calls = Collections.synchronizedList([])
        SandboxLifecyclePass pass = { dir, liveness ->
            calls << dir
            ''
        }
        def sandboxTick = new SandboxLifecycleTick(pass, livenessOracle, cloneDir, Duration.ofMinutes(5), sleeper, clock)

        when:
        sandboxTick.start()
        sleeper.awaitEntered()

        then:
        calls == [cloneDir]
    }

    def "a failing tick does not kill the thread; the loop retries next interval"() {
        given:
        def sleeper = new BlockingSleeper()
        SandboxLifecyclePass throwing = { dir, liveness ->
            throw new IllegalStateException('boom')
        }
        def sandboxTick = new SandboxLifecycleTick(throwing, livenessOracle, cloneDir, Duration.ofMinutes(5), sleeper, clock)

        when:
        sandboxTick.start()
        def firstSleep = sleeper.awaitEntered()

        then:
        firstSleep == Duration.ofMinutes(5)

        when:
        sleeper.releaseOne()
        def secondSleep = sleeper.awaitEntered()

        then:
        secondSleep == Duration.ofMinutes(5)
    }
}
