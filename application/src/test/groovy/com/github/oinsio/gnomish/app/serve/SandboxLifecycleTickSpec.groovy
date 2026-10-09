package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.lease.CachedOpenTaskListing
import com.github.oinsio.gnomish.app.lease.LivenessOracle
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.lease.StalenessMemory
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification
import spock.lang.Timeout

/**
 * {@link SandboxLifecycleTick#tick}, task 4.1 of add-serve-sandbox-lifecycle (design D7): the
 * per-tick call into {@link SandboxLifecyclePass} with a freshly recomputed {@link
 * LivenessOracle#evaluate} verdict, and the {@code lastRunAt} stamp. The loop around the tick is
 * {@link SandboxLifecycleTickLifecycleSpec}'s concern.
 */
@Timeout(10)
class SandboxLifecycleTickSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-08-07T12:00:00Z')

    def cloneDir = Path.of('/tmp/project')
    def clock = { -> NOW } as InstantSource
    def livenessOracle = new LivenessOracle(new CachedOpenTaskListing(), new StalenessMemory(new SystemMonotonicTime(), Duration.ofMinutes(1)))

    def "tick evaluates the liveness oracle fresh and runs the pass against this clone dir"() {
        given:
        List<List<Object>> calls = []
        SandboxLifecyclePass pass = { dir, liveness ->
            calls << [dir, liveness]
            ''
        }
        def sandboxTick = new SandboxLifecycleTick(pass, livenessOracle, cloneDir, Duration.ofMinutes(5), VirtualTimeEquipment.on(clock, Mock(Sleeper)))

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
        def advancingClock = { -> instants.next() } as InstantSource
        def sandboxTick = new SandboxLifecycleTick(SandboxLifecyclePass.NONE, livenessOracle, cloneDir, Duration.ofMinutes(5), VirtualTimeEquipment.on(advancingClock, Mock(Sleeper)))

        expect: 'construction seeds it, so the assertion below cannot pass by accident'
        sandboxTick.lastRunAt() == NOW

        when:
        sandboxTick.tick()

        then:
        sandboxTick.lastRunAt() == NOW.plusSeconds(300)
    }
}
