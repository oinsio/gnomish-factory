package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.serve.DaemonLifecycleState
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link ObservabilityWiring}: the daemon-lifetime handle {@code ServeCommand}/{@code
 * ServeShutdownWiring} drive (task 5.1). Constructed directly (package-private constructor) over
 * hand-built real collaborators, bypassing {@link ObservabilityAssembly} so this spec is scoped to
 * the wiring's OWN lifecycle-method behavior — most importantly {@link
 * ObservabilityWiring#finalizeStopped}'s idempotency, since both the drain-complete path and the
 * SIGTERM shutdown hook can reach it on the very same run (design: the JVM shutdown hook fires on
 * every exit).
 *
 * <p>Implements FR1, FR4, FR12 of add-serve-observability.
 */
// Bound every feature: a real SnapshotWriter thread on a 30s interval is started here, so a dropped
// wake/stop mutant must fail fast rather than block a test on the worker into a PIT TIMED_OUT.
@Timeout(10)
class ObservabilityWiringSpec extends Specification {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'gnomish-wiring-test'
    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-wiring-test-ab12cd', 'worker-1', '0.1.0')

    private Path ledgerFile(Instant at) {
        ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, LocalDate.ofInstant(at, ZoneOffset.UTC))
    }

    private ObservabilityWiring newWiring(Clock clock, LifecycleStateTracker lifecycleTracker) {
        ObservabilityWiringTestFixtures.build(
                homeDir, INSTANCE_NAME, INSTANCE, clock, lifecycleTracker, Duration.ofSeconds(30), true).wiring
    }

    def "finalizeStopped() transitions to stopped, writes the ledger line, and stops the writer — exactly once"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def clock = Clock.fixed(now, ZoneOffset.UTC)
        def lifecycleTracker = new LifecycleStateTracker(now)
        def wiring = newWiring(clock, lifecycleTracker)

        when: 'finalizeStopped runs once'
        wiring.finalizeStopped('signal')

        then: 'the lifecycle tracker moved to stopped with that reason'
        lifecycleTracker.view().state() == DaemonLifecycleState.STOPPED
        lifecycleTracker.view().reason() == 'signal'

        and: 'exactly one stopped ledger line was written'
        def lines = Files.readString(ledgerFile(now)).readLines().findAll {
            it.contains('"event":"stopped"')
        }
        lines.size() == 1
        lines[0].contains('"reason":"signal"')

        when: 'a second call arrives with a DIFFERENT reason (e.g. the JVM shutdown hook firing again on normal exit)'
        wiring.finalizeStopped('drainComplete')

        then: 'it is a no-op: the original reason and ledger line stand, no second line was appended'
        lifecycleTracker.view().reason() == 'signal'
        Files.readString(ledgerFile(now)).readLines().findAll {
            it.contains('"event":"stopped"')
        }.size() == 1
    }

    def "beginDraining()/beginStopping() move the lifecycle tracker through the intermediate states"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def clock = Clock.fixed(now, ZoneOffset.UTC)
        def lifecycleTracker = new LifecycleStateTracker(now)
        def wiring = newWiring(clock, lifecycleTracker)

        expect:
        lifecycleTracker.view().state() == DaemonLifecycleState.RUNNING

        when:
        wiring.beginDraining()

        then:
        lifecycleTracker.view().state() == DaemonLifecycleState.DRAINING

        when:
        wiring.beginStopping()

        then:
        lifecycleTracker.view().state() == DaemonLifecycleState.STOPPING
    }

    def "now() reflects the wiring's own clock"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def clock = Clock.fixed(now, ZoneOffset.UTC)
        def wiring = newWiring(clock, new LifecycleStateTracker(now))

        expect:
        wiring.now() == now
    }

    // NFR-O2 of add-serve-sandbox-lifecycle: the sweep's write point is the one the wiring holds,
    //     and it writes through this instance's own rotating appender — the same file every other
    //     ledger line rotates into.
    def "sweepLedgerWriter() returns a functional writer over the shared appender"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def clock = Clock.fixed(now, ZoneOffset.UTC)
        def wiring = newWiring(clock, new LifecycleStateTracker(now))

        when:
        wiring.sweepLedgerWriter().onTickCompleted(
                new com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickRecord(now, [:], [], 0, 0))

        then:
        Files.readString(ledgerFile(now)).contains('"type":"sweepTick"')
    }

    def "newRunSummaryLedgerWriter() returns a functional writer over the shared appender"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def clock = Clock.fixed(now, ZoneOffset.UTC)
        def wiring = newWiring(clock, new LifecycleStateTracker(now))
        def accumulator = new RunSummaryAccumulator()

        when:
        wiring.newRunSummaryLedgerWriter().write(accumulator, now)

        then:
        Files.readString(ledgerFile(now)).contains('"type":"runSummary"')
    }
}
