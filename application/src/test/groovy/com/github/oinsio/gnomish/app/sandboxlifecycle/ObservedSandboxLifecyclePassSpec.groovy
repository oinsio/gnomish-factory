package com.github.oinsio.gnomish.app.sandboxlifecycle

import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification

/**
 * {@link ObservedSandboxLifecyclePass}, tasks 6.1/6.2 of add-serve-sandbox-lifecycle: the daemon's
 * tick brackets — reset the tally, fan every verdict out to the vitals log and the ledger, then
 * hand the completed record to the tick sink — and the deliberate absence of a completed tick when
 * the pass fails.
 */
class ObservedSandboxLifecyclePassSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')
    static final Path CLONE_DIR = Path.of('/tmp/clone')

    def tickLog = new SweepTickLog(Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC), 20)
    def ledgerVerdicts = []
    def completedTicks = []

    private static SweepVerdict verdict(SweepVerdictCategory category) {
        new SweepVerdict(category, 'obj', 'main-box', 'tracked', 'task-1', 'reason', null)
    }

    /**
     * A pass whose SINK-taking overload is the one under observation. A Groovy closure coerces
     * only to the single abstract two-argument method, so the three-argument overload the daemon
     * actually calls needs a real implementation.
     */
    private static SandboxLifecyclePass passEmitting(Closure body) {
        new SandboxLifecyclePass() {
                    @Override
                    String run(Path cloneDir, LivenessVerdict liveness) {
                        throw new UnsupportedOperationException('the observed pass must use the sink overload')
                    }

                    @Override
                    String run(Path cloneDir, LivenessVerdict liveness, SweepVerdictListener extraSink) {
                        body.call(cloneDir, liveness, extraSink)
                    }
                }
    }

    private ObservedSandboxLifecyclePass observing(SandboxLifecyclePass delegate) {
        new ObservedSandboxLifecyclePass(
                delegate,
                tickLog, { SweepVerdict v ->
                    ledgerVerdicts << v
                } as SweepVerdictListener, { SweepTickRecord r ->
                    completedTicks << r
                } as SweepTickListener)
    }

    // NFR-O1, NFR-O2: one pass, both sinks, one tick summary, and the delegate's own summary line
    //     returned unchanged for take's finish report.
    def "a pass fans every verdict to both sinks and completes one tick"() {
        given:
        def emitted = verdict(SweepVerdictCategory.STOPPED_ORPHAN)
        def delegate = passEmitting { dir, liveness, sink ->
            sink.onVerdict(emitted)
            'sweep: 1 stopped-orphan'
        }

        when:
        def summary = observing(delegate).run(CLONE_DIR, new LivenessVerdict.NoVerdict())

        then:
        summary == 'sweep: 1 stopped-orphan'
        ledgerVerdicts == [emitted]
        tickLog.lastTick().counts() == [(SweepVerdictCategory.STOPPED_ORPHAN): 1]
        completedTicks.size() == 1
        completedTicks[0] == tickLog.lastTick()
    }

    // NFR-O1: the tally is reset per run, so the second tick never reports the first tick's work.
    def "each run brackets its own tick"() {
        given:
        def categories = [
            SweepVerdictCategory.DISPOSED_AGED,
            SweepVerdictCategory.CHECKED_ALIVE
        ].iterator()
        def delegate = passEmitting { dir, liveness, sink ->
            sink.onVerdict(verdict(categories.next()))
            ''
        }
        def pass = observing(delegate)

        when:
        pass.run(CLONE_DIR, new LivenessVerdict.NoVerdict())
        pass.run(CLONE_DIR, new LivenessVerdict.NoVerdict())

        then:
        completedTicks*.counts() == [
            [(SweepVerdictCategory.DISPOSED_AGED): 1],
            [(SweepVerdictCategory.CHECKED_ALIVE): 1]
        ]
    }

    // NFR-O3: a failed pass completes NO tick — a partial tally published as a finished tick would
    //     read as a healthy sweep that found less work, hiding the very stall the overdue alert
    //     exists to catch.
    def "a failing pass completes no tick and publishes no record"() {
        given:
        def delegate = passEmitting { dir, liveness, sink ->
            sink.onVerdict(verdict(SweepVerdictCategory.CHECKED_ALIVE))
            throw new IllegalStateException('docker unavailable')
        }

        when:
        observing(delegate).run(CLONE_DIR, new LivenessVerdict.NoVerdict())

        then:
        thrown(IllegalStateException)
        tickLog.lastTick() == null
        completedTicks.isEmpty()
    }

    // NFR-O1: the clone directory and liveness verdict reach the delegate untouched — the wrapper
    //     observes, it never re-decides.
    def "the delegate receives the caller's own clone directory and liveness verdict"() {
        given:
        def seen = []
        def delegate = passEmitting { dir, liveness, sink ->
            seen << [dir, liveness]
            ''
        }
        def liveness = new LivenessVerdict.Live(Set.of('task-1'))

        when:
        observing(delegate).run(CLONE_DIR, liveness)

        then:
        seen == [[CLONE_DIR, liveness]]
    }
}
