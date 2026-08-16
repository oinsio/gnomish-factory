package com.github.oinsio.gnomish.app.serve;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * {@link LifecycleStateTracker}'s own read model of the daemon's current lifecycle state (FR4 of
 * add-serve-observability), mirroring {@link FeedView}'s shape: the observed {@link
 * DaemonLifecycleState}, when it entered that state, and — only for {@link
 * DaemonLifecycleState#STOPPED} — why. Read by {@link LifecycleStateTracker#view()}; the {@code
 * serveobservability} package's {@code LifecycleSnapshotAssembler} turns this into the snapshot's
 * {@code lifecycle} section without this class knowing that package exists.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR4 of add-serve-observability.
 *
 * @param state the state observed by the tracker's last transition; never null
 * @param since the instant the daemon entered {@code state}; never null
 * @param reason why the daemon stopped; never null when {@code state} is {@link
 *     DaemonLifecycleState#STOPPED}, always null otherwise
 */
public record DaemonLifecycleView(
        DaemonLifecycleState state, Instant since, @Nullable String reason) {}
