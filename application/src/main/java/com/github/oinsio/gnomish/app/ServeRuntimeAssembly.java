package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.app.serve.SandboxLifecycleTick;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import com.github.oinsio.gnomish.serveobservability.SweepVital;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Orchestrates the whole {@code serve} daemon runtime once the tracker is live, over the leaf
 * builders {@link ServeAssembly} holds. Split from {@link ServeAssembly} (which keeps the individual
 * builders the specs also reuse) and from {@link ServeCommand} (which only starts the result and
 * branches drain-vs-forever) to keep each within the file-size limit (process-invariants.md); holds
 * no state of its own.
 *
 * <p>Implements FR13 of add-factory-serve. Implements FR1, FR4, FR7, FR8, FR9, FR12, D12 of
 * add-serve-observability.
 */
final class ServeRuntimeAssembly {

    private ServeRuntimeAssembly() {}

    /**
     * Wraps {@code liveTracker} in a {@link TrackerHealthTracker} (FR8, D12) shared by every
     * downstream caller, builds the one {@link TakeHeartbeat} whose {@code ClaimBeat}/{@code
     * ClaimLossFlag} every slot shares (FR13), then the {@link SlotLedger}, {@link TakeSlotRunner},
     * {@link FeedAutomaton}, {@link ServeShutdown}, {@link WorktreeJanitor} and the {@link
     * ObservabilityWiring}, and attaches the ledger writer to the slot runner.
     */
    static ServeRuntime assemble(
            ServeArguments serveArguments,
            Path worktreesRoot,
            Path homeDir,
            String taskIdMdcKey,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            TrackerAdapterFactory factory,
            Tracker liveTracker,
            InstanceId instanceId,
            int effectiveSlots,
            RunAssembly assembly,
            TaskGit git,
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            Clock clock,
            com.github.oinsio.gnomish.domain.engine.port.Clock feedClock,
            SandboxLifecyclePass sandboxLifecyclePass,
            ContainerTakeSupport containerTakeSupport,
            ClaimEpochBook epochs) {
        // FR8, D12: shared by every downstream caller (heartbeat, slot runner, feed automaton).
        TrackerHealthTracker trackerHealth = new TrackerHealthTracker(liveTracker, feedClock);
        Tracker tracker = trackerHealth;

        // FR1: stand-in bound to SnapshotWriter::markDirty by the writer ObservabilityAssembly
        // builds; built before the heartbeat so its state trigger (FR7) wakes it too.
        ForwardingDirtyNotifier dirtyNotifier = new ForwardingDirtyNotifier();

        // FR13: joins the assembly before TakeSlotRunner is built (reused for the daemon's lifetime).
        // FR7 (design D4): the heartbeat's state transitions wake the same writer.
        TakeHeartbeat heartbeat =
                TakeHeartbeat.forRun(tracker, trackerConfig, new ThreadSleeper(), dirtyNotifier::markDirty);
        RunAssembly serveAssembly = assembly.withExtraListener(heartbeat.progress());

        SlotLedger slotLedger = new SlotLedger(effectiveSlots, feedClock, dirtyNotifier);
        TakeSlotRunner slotRunner = ServeAssembly.slotRunner(
                serveArguments,
                worktreesRoot,
                taskIdMdcKey,
                definition,
                trackerConfig,
                factory,
                tracker,
                instanceId,
                serveAssembly,
                git,
                heartbeat,
                clock,
                containerTakeSupport,
                epochs);
        FeedAutomaton automaton = ServeAssembly.feedAutomaton(
                factoryProperties,
                serveProperties,
                feedClock,
                trackerConfig,
                tracker,
                instanceId,
                slotLedger,
                slotRunner,
                dirtyNotifier);
        ServeShutdown shutdown =
                ServeAssembly.shutdown(slotLedger, heartbeat.flag(), serveProperties, heartbeat.standingReaper());
        WorktreeJanitor worktreeJanitor =
                ServeAssembly.worktreeJanitor(serveArguments, worktreesRoot, serveProperties, slotLedger, git);
        // NFR-O1 of add-serve-sandbox-lifecycle: built before the observability wiring, which reads
        // it for `vitals.sweep`, and before the tick, which writes it — the log, not the tick
        // thread, is what the two share, so neither construction waits on the other. The reap
        // threshold every kept environment's remaining margin is measured against comes from the
        // SAME SandboxProperties the sweep policy itself was built from, so the dashboard's
        // time-to-reap can never disagree with the reaper's own decision.
        SweepTickLog sweepTickLog = new SweepTickLog(
                containerTakeSupport.sandboxProperties().keptReapAge(), clock, SweepVital.MAX_KEPT_INVENTORY);
        // FR1, FR4, FR7, FR9, FR12 of add-serve-observability (task 5.1, task 2.5).
        ObservabilityWiring observability = ObservabilityAssembly.assemble(
                factoryProperties,
                serveProperties,
                instanceId,
                homeDir,
                dirtyNotifier,
                slotLedger,
                effectiveSlots,
                automaton,
                trackerHealth,
                heartbeat.progress(),
                (InstanceHeartbeat) heartbeat.instance(),
                heartbeat.standingReaper(),
                worktreeJanitor,
                sweepTickLog,
                clock);
        SandboxLifecycleTick sandboxLifecycleTick = ServeAssembly.sandboxLifecycleTick(
                serveArguments,
                serveProperties,
                sandboxLifecyclePass,
                heartbeat.livenessOracle(),
                sweepTickLog,
                observability.sweepLedgerWriter(),
                observability.sweepLedgerWriter());
        slotRunner.attachLedgerWriter(observability.taskOutcomeLedgerWriter());
        return new ServeRuntime(
                automaton,
                slotRunner,
                shutdown,
                worktreeJanitor,
                heartbeat.standingReaper(),
                observability,
                sandboxLifecycleTick);
    }
}
