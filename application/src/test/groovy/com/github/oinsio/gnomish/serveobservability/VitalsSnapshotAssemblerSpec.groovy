package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.lease.ClaimLostSink
import com.github.oinsio.gnomish.app.lease.HeartbeatProgress
import com.github.oinsio.gnomish.app.lease.HeartbeatWorkerState
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link VitalsSnapshotAssembler}: translates the three thread-owning collaborators (design D3)
 * into the snapshot's {@code vitals} section (FR7) — {@link InstanceHeartbeat}'s state mapped by
 * enum name onto this package's {@link HeartbeatState}, mirroring {@link
 * FeedSnapshotAssembler}'s {@code FeedState}/{@code FeedPhase} split, with the remaining fields
 * carried verbatim from {@link StandingReaper} and {@link WorktreeJanitor}.
 *
 * <p>Implements FR7 of add-serve-observability.
 */
class VitalsSnapshotAssemblerSpec extends Specification {

    @TempDir
    Path tempDir

    private static final Duration INTERVAL = Duration.ofMinutes(5)

    private final Tracker tracker = Stub(Tracker)
    private final VirtualClock clock = new VirtualClock()

    private InstanceHeartbeat newHeartbeat() {
        new InstanceHeartbeat(
                tracker,
                new HeartbeatProgress(),
                { Duration d -> } as Sleeper,
                clock,
                INTERVAL,
                ClaimLostSink.IGNORE)
    }

    private StandingReaper newReaper() {
        new StandingReaper(
                ReaperDuty.NONE, { Duration d -> } as Sleeper, INTERVAL,
                { -> [] }, clock)
    }

    private WorktreeJanitor newJanitor() {
        new WorktreeJanitor(
                tempDir.resolve('worktrees'),
                tempDir.resolve('clone'),
                Duration.ofDays(1),
                { String key -> } as TaskEnvironmentDisposal,
                clock,
                { Duration d -> } as Sleeper,
                { -> Set.of() })
    }

    // FR7: the assembled vitals carry every field verbatim from the three collaborators.
    def "assembles the vitals section field-for-field from the given collaborators"() {
        given:
        def heartbeat = newHeartbeat()
        def ref = new TaskRef('github:o/r#1')
        heartbeat.register(ref)
        def reaper = newReaper()
        def janitor = newJanitor()

        when:
        def vitals = VitalsSnapshotAssembler.assemble(
                heartbeat,
                reaper,
                janitor,
                new SweepTickLog(Duration.ofDays(7), Clock.systemUTC(), 20),
                Duration.ofMinutes(5))

        then:
        vitals.heartbeat().state() == HeartbeatState.RUNNING
        vitals.heartbeat().lastTickAt() == heartbeat.lastTickAt()
        vitals.heartbeat().heldClaims() == 1
        vitals.reaper().lastRunAt() == reaper.lastRunAt()
        vitals.reaper().restartCount() == reaper.restartCount()
        vitals.reaper().intervalSeconds() == reaper.interval().toSeconds()
        vitals.janitor().lastRunAt() == janitor.lastRunAt()

        and: 'NFR-O1 of add-serve-sandbox-lifecycle: no tick has completed, so the sweep entry is absent'
        vitals.sweep() == null

        cleanup:
        heartbeat.unregister(ref)
    }

    // FR7, D3: every HeartbeatWorkerState value maps to the HeartbeatState of the same name —
    //     the two enums are kept distinct (app.lease carries no dependency on serveobservability)
    //     but must stay in lockstep.
    def "every HeartbeatWorkerState value maps to the HeartbeatState of the same name"() {
        expect:
        HeartbeatWorkerState.values().every { state ->
            HeartbeatState.valueOf(state.name()) != null
        }
    }
}
