package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.JanitorVital
import com.github.oinsio.gnomish.serveobservability.LifecycleSnapshotAssembler
import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.LifecycleLedgerWriter
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.SnapshotWriter
import com.github.oinsio.gnomish.serveobservability.writer.SweepLedgerWriter
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Shared collaborator-graph construction for {@link ObservabilityWiringSpec} and {@link
 * ObservabilityWiringStartStopSpec}: both hand-build the same real {@link ObservabilityWiring} —
 * snapshot writer, rotating ledger appender, lifecycle/task-outcome/sweep ledger writers — bypassing
 * {@link ObservabilityAssembly} so each spec stays scoped to the wiring's own behavior. The two
 * specs differ only in the snapshot interval and whether the snapshot writer is started before the
 * wiring is handed back.
 */
class ObservabilityWiringTestFixtures {

    static Snapshot fixtureSnapshot(LifecycleStateTracker tracker, InstanceInfo instance) {
        def feed = new FeedSnapshot(FeedPhase.IDLE_EMPTY, Instant.EPOCH, Instant.EPOCH, 0, 2)
        def vitals = new VitalsSnapshot(
                new HeartbeatVital(HeartbeatState.RUNNING, Instant.EPOCH, 0),
                new ReaperVital(Instant.EPOCH, 0, 300L),
                new JanitorVital(Instant.EPOCH))
        return new Snapshot(1, Instant.EPOCH, 0L, instance, LifecycleSnapshotAssembler.assemble(tracker), feed,
                new SlotsSnapshot(2, []), vitals, new TrackerHealth(null, 0))
    }

    static class Built {
        ObservabilityWiring wiring
        Path snapshotFile
        TaskOutcomeLedgerWriter taskOutcomeLedgerWriter
    }

    static Built build(Path homeDir, String instanceName, InstanceInfo instance, Clock clock,
            LifecycleStateTracker lifecycleTracker, Duration snapshotInterval, boolean startSnapshotWriter) {
        def snapshotFile = homeDir.resolve('snapshot.json')
        def snapshotWriter = new SnapshotWriter(
                snapshotFile,
                { -> fixtureSnapshot(lifecycleTracker, instance) },
                new SnapshotJsonMapper(),
                snapshotInterval,
                clock,
                0)
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(homeDir.resolve('placeholder'), new LedgerJsonMapper()), homeDir, instanceName, clock)
        def taskOutcomeLedgerWriter = new TaskOutcomeLedgerWriter(new SlotLedger(1), appender, instance, clock)
        if (startSnapshotWriter) {
            snapshotWriter.start()
        }
        def wiring = new ObservabilityWiring(
                lifecycleTracker,
                snapshotWriter,
                new LifecycleLedgerWriter(appender, instance, clock),
                taskOutcomeLedgerWriter,
                new SweepLedgerWriter(appender, instance, clock),
                appender,
                instance,
                clock)
        return new Built(wiring: wiring, snapshotFile: snapshotFile,
        taskOutcomeLedgerWriter: taskOutcomeLedgerWriter)
    }
}
