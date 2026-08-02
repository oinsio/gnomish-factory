package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.WorktreeEnvironmentDisposal;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.RealProcessTreeKiller;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Random;

/**
 * Builds the per-invocation collaborators {@link ServeCommand#run} assembles once the tracker
 * is live: the reused-for-the-daemon's-whole-lifetime {@link TakeSlotRunner} (FR13's shared
 * {@code ClaimBeat}/{@code ClaimLossFlag} wiring), the {@link FeedAutomaton} that drives it, and
 * the {@link ServeShutdown} SIGTERM sequence (FR11, D9) that shares the same {@link SlotLedger}
 * and {@link ClaimLossFlag}. Extracted purely to keep {@link ServeCommand} within the file-size
 * limit (process-invariants.md) — holds no state of its own.
 *
 * <p>Implements FR2, FR13 of add-factory-serve. Implements FR11, D9 of add-factory-serve.
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
            ManualRunAssembly serveAssembly,
            TakeHeartbeat heartbeat,
            Clock clock) {
        AbortHandler abortHandler = new AbortHandler(tracker, clock);
        return new TakeSlotRunner(
                serveAssembly,
                serveArguments.dir(),
                worktreesRoot,
                definition,
                abortHandler,
                trackerConfig.abortThreshold(),
                taskIdMdcKey,
                factory.credentialEnvVars(),
                heartbeat.instance(),
                heartbeat.flag(),
                tracker,
                instanceId);
    }

    static FeedAutomaton feedAutomaton(
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            com.github.oinsio.gnomish.domain.engine.port.Clock feedClock,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            SlotLedger slotLedger,
            TakeSlotRunner slotRunner) {
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
                new Random());
    }

    /**
     * FR11, D9: the SIGTERM shutdown coordinator, sharing {@code slotLedger} and {@code
     * claimLossFlag} with the {@link FeedAutomaton}/{@link TakeSlotRunner} this invocation already
     * assembled, so flagging a slot's claim here reacts at the SAME round-boundary check every
     * other claim-loss reaches.
     */
    static ServeShutdown shutdown(SlotLedger slotLedger, ClaimLossFlag claimLossFlag, ServeProperties serveProperties) {
        return new ServeShutdown(
                slotLedger, claimLossFlag, serveProperties.sigtermGrace(), new RealProcessTreeKiller());
    }

    /**
     * FR14, D10: the worktree janitor, wired over a fresh {@link GitProcessRunner} and disposing
     * through {@link WorktreeEnvironmentDisposal} (a host worktree, today's only realization of
     * {@code TaskEnvironmentDisposal}). Held tasks are read fresh from {@code slotLedger} on every
     * tick, so a task claimed after the janitor starts is still protected.
     */
    static WorktreeJanitor worktreeJanitor(
            ServeArguments serveArguments, Path worktreesRoot, ServeProperties serveProperties, SlotLedger slotLedger) {
        var disposal = new WorktreeEnvironmentDisposal(new GitProcessRunner(), serveArguments.dir(), worktreesRoot);
        return new WorktreeJanitor(
                worktreesRoot,
                serveArguments.dir(),
                serveProperties.worktreeAgeThreshold(),
                disposal,
                new SystemClock(),
                new ThreadSleeper(),
                slotLedger::occupiedRefs);
    }
}
