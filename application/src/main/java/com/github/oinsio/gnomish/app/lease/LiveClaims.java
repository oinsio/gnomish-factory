package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Collection;

/**
 * The role a {@link StandingReaper} needs from this instance's heartbeat: the claims it is beating
 * live right now, which the reaper excludes from staleness observation (design D3 of
 * fix-reaper-idle-liveness). A seam a spec fakes is a role interface in the owning module, never a
 * JDK functional type (clause (iii) of design D22 of supervise-daemon-loops-and-embed-dashboard) —
 * this replaces a {@code Supplier<Collection<TaskRef>>}. The production answer is {@link
 * InstanceHeartbeat#liveClaimsSnapshot()}, which is empty once the beat thread is not running, so a
 * dead heartbeat's claims are never read as live.
 *
 * <p>Implements FR2 of fix-reaper-idle-liveness; FR18 of supervise-daemon-loops-and-embed-dashboard.
 */
@FunctionalInterface
public interface LiveClaims {

    /**
     * A snapshot of the claims this instance is beating live, read fresh on every reaper tick.
     *
     * @return the live claims; never null, empty when none are held or the heartbeat is not running
     */
    Collection<TaskRef> snapshot();
}
