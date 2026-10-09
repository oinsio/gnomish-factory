package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop
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
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private final AtomicBoolean dies = new AtomicBoolean(true)

    /** A failure the reaper loop's guard cannot even describe: reporting it throws, so the thread dies. */
    private static final class Unrenderable extends Error {
        @Override
        String toString() {
            throw new IllegalStateException('the failure cannot be rendered')
        }
    }

    private InstanceHeartbeat newHeartbeat() {
        new InstanceHeartbeat(
                tracker,
                new HeartbeatProgress(),
                VirtualTimeEquipment.on(clock, { Duration d -> } as Sleeper),
                INTERVAL,
                ClaimLostSink.IGNORE)
    }

    private StandingReaper newReaper() {
        new StandingReaper(
                ReaperDuty.NONE, INTERVAL, {
                    -> []
                }, VirtualTimeEquipment.on(clock, { Duration d -> } as Sleeper))
    }

    private WorktreeJanitor newJanitor() {
        new WorktreeJanitor(
                RegisteredCloneFixture.unregistered(tempDir.resolve('home'), tempDir.resolve('clone')),
                Duration.ofDays(1),
                { String key -> } as TaskEnvironmentDisposal,
                VirtualTimeEquipment.on(clock, { Duration d -> } as Sleeper),
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
                new SweepTickLog(Duration.ofDays(7), clock, 20),
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

    // FR6 of supervise-daemon-loops-and-embed-dashboard (daemon-supervision "Reaper restarts stay
    //     visible in the snapshot"): the reaper's thread dies and is respawned by its supervised
    //     loop; a snapshot assembled afterwards shows the grown vitals.reaper.restartCount.
    def "a snapshot after the reaper's thread was respawned shows a grown restartCount"() {
        given: 'a reaper whose first tick dies past its loop guard, and whose second tick stops it'
        def logs = LogCaptureSupport.attach(SupervisedLoop)
        def secondTick = new CountDownLatch(1)
        StandingReaper reaper
        def duty = { Collection<TaskRef> own ->
            if (dies.getAndSet(false)) {
                throw new Unrenderable()
            }
            reaper.stop()
            secondTick.countDown()
        } as ReaperDuty
        reaper = new StandingReaper(duty, INTERVAL, {
            -> []
        }, VirtualTimeEquipment.on(clock, { Duration d -> } as Sleeper))
        def heartbeat = newHeartbeat()

        when:
        reaper.start()
        assert secondTick.await(5, TimeUnit.SECONDS)
        def vitals = VitalsSnapshotAssembler.assemble(
                heartbeat,
                reaper,
                newJanitor(),
                new SweepTickLog(Duration.ofDays(7), clock, 20),
                Duration.ofMinutes(5))

        then:
        vitals.reaper().restartCount() == 1
        logs.list.any {
            it.formattedMessage.startsWith(OperatorEvent.DAEMON_LOOP_WORKER_DIED.head()) &&
            it.MDCPropertyMap['component'] == 'reaper'
        }

        cleanup:
        logs?.detach()
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
