package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat;
import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;

/**
 * Builds the snapshot's {@code vitals} section (FR7) from the three thread-owning collaborators
 * design D3 names: {@link InstanceHeartbeat} (state via {@link
 * InstanceHeartbeat#state()}, mapped onto this package's decoupled {@link HeartbeatState} by enum
 * name — mirrors {@link FeedSnapshotAssembler}'s {@code FeedState}/{@code FeedPhase} split — plus
 * {@code lastTickAt}/{@code heldClaims} carried verbatim), {@link StandingReaper} ({@code
 * lastRunAt}/{@code restartCount} verbatim plus its tick {@code interval} in seconds), and {@link
 * WorktreeJanitor} ({@code lastRunAt} verbatim).
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
     * reaper}, and {@code janitor}.
     *
     * @param heartbeat the instance-level claim heartbeat; never null
     * @param reaper the standing reaper thread; never null
     * @param janitor the worktree janitor thread; never null
     * @return the assembled {@link VitalsSnapshot}; never null
     */
    public static VitalsSnapshot assemble(InstanceHeartbeat heartbeat, StandingReaper reaper, WorktreeJanitor janitor) {
        return new VitalsSnapshot(
                new HeartbeatVital(
                        HeartbeatState.valueOf(heartbeat.state().name()),
                        heartbeat.lastTickAt(),
                        heartbeat.heldClaims()),
                new ReaperVital(
                        reaper.lastRunAt(),
                        reaper.restartCount(),
                        reaper.interval().toSeconds()),
                new JanitorVital(janitor.lastRunAt()));
    }
}
