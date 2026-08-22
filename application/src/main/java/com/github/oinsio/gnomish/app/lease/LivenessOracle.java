package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The tracked-object liveness oracle (proposal FR3, design D1): a {@code tracked} Docker object
 * is alive iff its task's claim heartbeat is fresh. Rather than deriving a reverse key-to-task
 * mapping, the live environment-key set is computed FORWARD each call from the reaper's most
 * recently published listing — {@link CachedOpenTaskListing} — filtered against {@link
 * StalenessMemory#staleRefs()}, the SAME cross-tick observation memory the claim reaper already
 * drives (D1: "that memory is claim-heartbeat mechanics reused as-is, not a sweep cache"). No
 * second {@code listOpen} call (NFR-C2); no mutation of the shared memory (a pure read).
 *
 * <p>A ref counts unowned exactly when it is latched stale — the identical condition that
 * licenses the claim reaper's own takeover ({@code removeStaleClaim}), so a stale-claim task's
 * objects classify unowned exactly when takeover is licensed (task 2.3, FR3).
 *
 * <p>Stateless: its own state lives entirely in the injected collaborators, and {@link #evaluate()}
 * may be called from any thread, any number of times — the sweep thread's call races the reaper
 * thread's own ticks by design. That is safe only because BOTH collaborators carry their own
 * synchronization: {@link CachedOpenTaskListing} publishes through an {@code AtomicReference}, and
 * {@link StalenessMemory} guards its observation map so {@code staleRefs()} cannot iterate a map the
 * reaper is restructuring. The oracle adds no locking of its own and must not be given a
 * single-threaded collaborator.
 *
 * <p>Implements FR3, NFR-C2, NFR-R1 of add-serve-sandbox-lifecycle.
 */
public final class LivenessOracle {

    private final CachedOpenTaskListing listing;
    private final StalenessMemory memory;

    /**
     * @param listing the reaper's most recently published listing (or its absence); never null
     * @param memory the SAME staleness memory instance the claim reaper drives; never null
     */
    public LivenessOracle(CachedOpenTaskListing listing, StalenessMemory memory) {
        this.listing = listing;
        this.memory = memory;
    }

    /**
     * Recomputes the live environment-key set from the current listing (task 2.1). A {@code
     * listOpen} outage yields {@link LivenessVerdict.NoVerdict} — fail-closed, never an empty
     * live set (task 2.2, NFR-R1).
     *
     * @return the current liveness verdict; never null
     */
    public LivenessVerdict evaluate() {
        return switch (listing.current()) {
            case CachedOpenTaskListing.Listing.Failed ignored -> new LivenessVerdict.NoVerdict();
            case CachedOpenTaskListing.Listing.Observed observed -> liveVerdict(observed.openTasks());
        };
    }

    private LivenessVerdict liveVerdict(List<OpenTask> openTasks) {
        Set<TaskRef> stale = memory.staleRefs();
        Set<String> liveKeys = openTasks.stream()
                .filter(task -> !stale.contains(task.ref()))
                .map(task -> TaskIdSanitizer.sanitize(task.ref().id()))
                .collect(Collectors.toUnmodifiableSet());
        return new LivenessVerdict.Live(liveKeys);
    }
}
