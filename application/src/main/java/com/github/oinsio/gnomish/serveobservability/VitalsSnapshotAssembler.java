package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat;
import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;
import java.time.Duration;

/**
 * Builds the snapshot's {@code vitals} section (FR7) from the three thread-owning collaborators
 * design D3 names: {@link InstanceHeartbeat} (state via {@link
 * InstanceHeartbeat#state()}, mapped onto this package's decoupled {@link HeartbeatState} by enum
 * name — mirrors {@link FeedSnapshotAssembler}'s {@code FeedState}/{@code FeedPhase} split — plus
 * {@code lastTickAt}/{@code heldClaims} carried verbatim), {@link StandingReaper} ({@code
 * lastRunAt}/{@code restartCount} verbatim plus its tick {@code interval} in seconds), and {@link
 * WorktreeJanitor} ({@code lastRunAt} verbatim) — and, since
 * add-serve-sandbox-lifecycle, the sandbox-lifecycle sweep's {@link SweepTickLog}, mapped by
 * {@link SweepVitalAssembler} (NFR-O1). The sweep entry's source is the tick LOG, not the tick
 * thread: the log is written at the end of every completed pass, so the vitals never depend on a
 * scheduler the observability wiring is itself constructed before.
 *
 * <p>Stateless: holds no fields, only assembles a fresh {@link VitalsSnapshot} from the
 * collaborators handed to it on each call.
 *
 * <p>Implements FR7 of add-serve-observability.
 */
public final class VitalsSnapshotAssembler {

    private VitalsSnapshotAssembler() {}

    /**
     * Assembles the {@code vitals} section from the current state of {@code heartbeat}, {@code
     * reaper}, {@code janitor}, and the sweep's tick log.
     *
     * @param heartbeat the instance-level claim heartbeat; never null
     * @param reaper the standing reaper thread; never null
     * @param janitor the worktree janitor thread; never null
     * @param sweepTickLog the sandbox-lifecycle sweep's per-tick record; never null
     * @param sweepInterval the sweep tick cadence, carried as the reader's staleness yardstick
     * @return the assembled {@link VitalsSnapshot}; never null
     */
    public static VitalsSnapshot assemble(
            InstanceHeartbeat heartbeat,
            StandingReaper reaper,
            WorktreeJanitor janitor,
            SweepTickLog sweepTickLog,
            Duration sweepInterval) {
        return new VitalsSnapshot(
                new HeartbeatVital(
                        HeartbeatState.valueOf(heartbeat.state().name()),
                        heartbeat.lastTickAt(),
                        heartbeat.heldClaims()),
                new ReaperVital(
                        reaper.lastRunAt(),
                        reaper.restartCount(),
                        reaper.interval().toSeconds()),
                new JanitorVital(janitor.lastRunAt()),
                SweepVitalAssembler.assemble(sweepTickLog, sweepInterval));
    }
}
