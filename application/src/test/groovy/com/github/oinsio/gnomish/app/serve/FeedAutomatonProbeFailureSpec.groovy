package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.testfixtures.logging.RepeatSuppressorFixture
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The feed survives a condition probe that throws (FR15 of add-sandbox-base-image): a probe that
 * cannot answer — the image inspection refused because the tag is gone — is a failed probe, not the
 * end of the feed thread.
 */
@Timeout(10)
class FeedAutomatonProbeFailureSpec extends Specification {

    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final Duration CAP = Duration.ofMinutes(10)

    private final VirtualClock clock = new VirtualClock()
    private final AtomicInteger reads = new AtomicInteger()
    private final AtomicInteger probes = new AtomicInteger()
    private final List<Duration> sleeps = []
    private final Tracker tracker = [
        listReady: { int limit -> reads.incrementAndGet(); [] },
        listOpen : { -> reads.incrementAndGet(); [] },
    ] as Tracker
    private final SlotLedger ledger = new SlotLedger(1, clock)

    private FeedAutomaton automaton(DaemonConditions conditions) {
        def sleeper = { Duration d ->
            sleeps << d; clock.advance(d)
        } as Sleeper
        new FeedAssembly(VirtualTimeEquipment.on(clock, sleeper),
                new IdleTiming(IDLE, Duration.ofMinutes(2), Duration.ofHours(1), new Random(0)), 5)
                .feedAutomaton(tracker, InstanceId.generate('gnome'), ledger, { TaskRef ref -> } as SlotRunner,
                DirtyNotifier.NOOP, conditions)
    }

    private DaemonCondition condition(ConditionSubject subject) {
        new DaemonCondition(subject, clock, new Random(0), IDLE, CAP,
                new RemoteOutageWiring(subject.kind().name(), RepeatSuppressorFixture.quiet(), Duration.ofHours(1), {},
                RemoteOutageLedgerSink.NONE))
    }

    /** An open image-contract condition whose probe never answers: the inspect refuses every time. */
    private DaemonConditions heldByThrowingProbe() {
        def rechecks = { DaemonConditionKind kind ->
            probes.incrementAndGet()
            throw new IllegalStateException('image inspect refused: No such image')
        } as PreconditionRecheck
        def set = new DaemonConditions(
                condition(ConditionSubject.remote(BaseRefGit.UNWIRED, Path.of('.'))),
                [
                    condition(ConditionSubject.precondition(DaemonConditionKind.IMAGE_CONTRACT, rechecks))
                ])
        set.report(new TakeResult.OperatorPreconditionUnmet(UntrustedText.factory('contract'), DaemonConditionKind.IMAGE_CONTRACT))
        set
    }

    // Scenario: serve/factory-serve / A probe that cannot answer is a failed probe
    def "a probe that throws is a failed probe: the feed stays held, waits, and steps again"() {
        given:
        def feed = automaton(heldByThrowingProbe())
        clock.advance(IDLE)

        when: 'the feed runs the cycle whose probe throws, and the next one'
        def first = feed.step()
        def second = feed.step()

        then: 'the feed thread is alive: both cycles end in the held state'
        first == FeedState.IDLE_BLOCKED
        second == FeedState.IDLE_BLOCKED
        probes.get() >= 1
        reads.get() == 0
        ledger.freeSlots() == 1
        sleeps.size() == 2
    }
}
