package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.lease.LivenessOracle;
import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.sandboxlifecycle.ObservedSandboxLifecyclePass;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickListener;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.RealProcessTreeKiller;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.app.serve.SandboxLifecycleTick;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Random;

/**
 * The leaf builders {@link ServeRuntimeAssembly} composes into the {@code serve} daemon runtime,
 * split out so both stay within the file-size limit (process-invariants.md) and so the specs can
 * drive each builder in isolation. Holds no state of its own.
 *
 * <p>Implements FR2, FR11, FR13, D9 of add-factory-serve.
 */
final class ServeAssembly {

    private ServeAssembly() {}

    /** FR13: {@code heartbeat}'s {@code ClaimBeat}/{@code ClaimLossFlag} are shared by every slot. */
    static TakeSlotRunner slotRunner(
            ServeArguments serveArguments,
            Path worktreesRoot,
            String taskIdMdcKey,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            TrackerAdapterFactory factory,
            Tracker tracker,
            InstanceId instanceId,
            RunAssembly serveAssembly,
            TaskGit git,
            TakeHeartbeat heartbeat,
            Clock clock,
            ContainerTakeSupport containerTakeSupport) {
        AbortHandler abortHandler = new AbortHandler(tracker, clock);
        return new TakeSlotRunner(
                serveAssembly,
                git,
                serveArguments.dir(),
                worktreesRoot,
                definition,
                abortHandler,
                trackerConfig.abortThreshold(),
                taskIdMdcKey,
                factory.credentialEnvVars(trackerConfig),
                heartbeat.instance(),
                heartbeat.flag(),
                tracker,
                instanceId,
                containerTakeSupport);
    }

    static FeedAutomaton feedAutomaton(
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            com.github.oinsio.gnomish.domain.engine.port.Clock feedClock,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            SlotLedger slotLedger,
            TakeSlotRunner slotRunner,
            com.github.oinsio.gnomish.app.serve.DirtyNotifier dirtyNotifier) {
        FactoryProperties.Tracker trackerProperties = factoryProperties.tracker();
        return new FeedAutomaton(
                tracker,
                instanceId,
                slotLedger,
                slotRunner,
                new ThreadSleeper(),
                feedClock,
                trackerProperties.abortBackoffBase(),
                trackerProperties.abortBackoffCap(),
                serveProperties.idlePollInterval(),
                trackerConfig.wipLimit(),
                new Random(),
                dirtyNotifier);
    }

    /**
     * FR11, D9: the SIGTERM shutdown coordinator, sharing {@code slotLedger} and {@code
     * claimLossFlag} with the {@link FeedAutomaton}/{@link TakeSlotRunner}, so flagging a slot's
     * claim here reacts at the SAME round-boundary check every other claim-loss reaches.
     */
    static ServeShutdown shutdown(
            SlotLedger slotLedger,
            ClaimLossFlag claimLossFlag,
            ServeProperties serveProperties,
            StandingReaper standingReaper) {
        return new ServeShutdown(
                slotLedger, claimLossFlag, serveProperties.sigtermGrace(), new RealProcessTreeKiller(), standingReaper);
    }

    /**
     * FR14, D10: the worktree janitor, disposing through the task-git port's own bound disposer
     * (task 4.4 of split-into-modules — it used to build the git-subprocess one here). Held tasks
     * are read fresh from {@code slotLedger} every tick, so a task claimed after the janitor starts
     * is still protected.
     */
    static WorktreeJanitor worktreeJanitor(
            ServeArguments serveArguments,
            Path worktreesRoot,
            ServeProperties serveProperties,
            SlotLedger slotLedger,
            TaskGit git) {
        var disposal = git.worktrees().environmentDisposal(serveArguments.dir(), worktreesRoot);
        return new WorktreeJanitor(
                worktreesRoot,
                serveArguments.dir(),
                serveProperties.worktreeAgeThreshold(),
                disposal,
                new SystemClock(),
                new ThreadSleeper(),
                slotLedger::occupiedRefs);
    }

    /**
     * FR6, NFR-P1, design D7 of add-serve-sandbox-lifecycle: the sweep-lifecycle tick, its own
     * virtual thread beside the worktree janitor's — disjoint object populations, disjoint
     * cleaners. {@code sandboxLifecyclePass} is {@link SandboxLifecyclePass#NONE} on a host-only
     * install (no {@code factory.sandbox.image} configured), so the tick still runs but is a no-op
     * every cadence.
     *
     * <p>NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle: the daemon — and only the daemon — brackets
     * each pass as an observed tick, so the snapshot's {@code vitals.sweep} and the ledger's sweep
     * lines both come from the same evaluation the scheduler already runs. A host-only install is
     * deliberately left UNobserved: its tick has no sandbox to sweep, so an all-zero vital and a
     * ledger line every cadence would report on a subsystem that does not exist on that host —
     * "no sweep data yet" is the honest reading there.
     */
    static SandboxLifecycleTick sandboxLifecycleTick(
            ServeArguments serveArguments,
            ServeProperties serveProperties,
            SandboxLifecyclePass sandboxLifecyclePass,
            LivenessOracle livenessOracle,
            SweepTickLog sweepTickLog,
            SweepVerdictListener sweepVerdictSink,
            SweepTickListener sweepTickSink) {
        SandboxLifecyclePass observed = Objects.equals(sandboxLifecyclePass, SandboxLifecyclePass.NONE)
                ? sandboxLifecyclePass
                : new ObservedSandboxLifecyclePass(sandboxLifecyclePass, sweepTickLog, sweepVerdictSink, sweepTickSink);
        return new SandboxLifecycleTick(
                observed,
                livenessOracle,
                serveArguments.dir(),
                serveProperties.sandboxSweepInterval(),
                new ThreadSleeper(),
                new SystemClock());
    }
}
