package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.daemon.LoopOrder;
import com.github.oinsio.gnomish.app.daemon.LoopShape;
import com.github.oinsio.gnomish.app.daemon.LoopWait;
import com.github.oinsio.gnomish.app.daemon.RestartBackoff;
import com.github.oinsio.gnomish.app.daemon.RestartPolicy;
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.logtext.MdcAwareThread;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.status.DaemonComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single worktree cleaner component (design D10, FR14): at {@code serve} startup and every
 * hour after, it scans its registered clone's own worktree folder ({@link
 * RegisteredClone#worktrees()}) and {@link TaskEnvironmentDisposal#dispose disposes} of every task
 * environment whose most recent file activity is older than the age threshold — except one
 * occupying a slot of THIS instance, skipped regardless of age. Held tasks are read fresh on every
 * tick ({@code heldRefs}, typically {@code SlotLedger::occupiedRefs}), so a task claimed after the
 * janitor started is still protected. There is no "is this task ended" check against the tracker:
 * age plus "not held here" is the whole policy, deliberately simple, since a disposal too early
 * only costs a re-clone on resume (design D10 risk note). Worktrees are instance-local, and another
 * clone's folder is never listed (FR9, NFR-R2 of add-project-registry).
 *
 * <p><b>The thread is a supervised daemon loop</b> (design D1, D7 of
 * supervise-daemon-loops-and-embed-dashboard). This class owns only its tick and its {@code
 * lastRunAt}; the thread, the guard, the stop and the restart belong to the {@link SupervisedLoop}
 * it holds: tick → wait on a {@link LoopWait.FixedInterval} of {@link #TICK_INTERVAL}, framed as
 * {@link DaemonComponent#JANITOR}, under {@link RestartPolicy.Unbounded} whose shared cap ({@link RestartBackoff#MAX_BACKOFF}) wins
 * over the hour from the first respawn on. A failed tick, an {@code Error} included, is the loop's
 * {@code DAEMON_LOOP_TICK_FAILED} with {@code component=janitor}, and the next tick tries again;
 * the tick's own scan and held-ref lines keep their codes.
 *
 * <p>Implements FR14 of add-factory-serve (design D10); FR9, NFR-R2 of add-project-registry; FR6
 * of supervise-daemon-loops-and-embed-dashboard.
 */
public final class WorktreeJanitor {

    private static final Logger log = LoggerFactory.getLogger(WorktreeJanitor.class);

    /** The fixed recurring cadence after the immediate startup tick (design D10). */
    static final Duration TICK_INTERVAL = Duration.ofHours(1);

    private final Path cloneWorktrees;
    private final Duration ageThreshold;
    private final TaskEnvironmentDisposal disposal;
    private final InstantSource clock;
    private final OccupiedSlots heldRefs;
    private final SupervisedLoop loop;
    private volatile Instant lastRunAt;

    /**
     * @param clone the registered clone whose own worktree folder is swept, and only that folder
     * @param ageThreshold the minimum time since an environment's last file activity before it is
     *     eligible for disposal ({@code factory.serve.worktree-age-threshold}, design D10)
     * @param disposal the dispose-shaped seam an eligible environment's key is handed to
     * @param time the time equipment (virtual under test): its clock is the source of "now" the age
     *     comparison reads and times the loop's failure roll-ups, its sleeper waits the tick interval
     *     and the restart backoff (design D2, D20 of supervise-daemon-loops-and-embed-dashboard)
     * @param heldRefs answers, fresh on every tick, the tasks currently occupying a slot of this
     *     instance — never disposed regardless of age
     */
    public WorktreeJanitor(
            RegisteredClone clone,
            Duration ageThreshold,
            TaskEnvironmentDisposal disposal,
            TimeEquipment time,
            OccupiedSlots heldRefs) {
        this.cloneWorktrees = clone.worktrees();
        this.ageThreshold = ageThreshold;
        this.disposal = disposal;
        this.clock = time.clock();
        this.heldRefs = heldRefs;
        this.lastRunAt = clock.instant();
        LoopShape shape = new LoopShape(
                DaemonComponent.JANITOR,
                LoopOrder.TICK_THEN_WAIT,
                new LoopWait.FixedInterval(time.sleeper(), TICK_INTERVAL),
                new RestartPolicy.Unbounded(TICK_INTERVAL));
        this.loop = new SupervisedLoop(shape, this::tick, time);
    }

    /**
     * Starts the janitor: one immediate tick (the startup scan, FR14) followed by a tick every
     * {@link #TICK_INTERVAL} thereafter, for the daemon's whole lifetime. Idempotent.
     */
    public void start() {
        loop.start();
    }

    /**
     * Stops the janitor and returns at once: an interval wait in progress is cut short, a tick in
     * progress completes, and no respawn follows (design D4). Idempotent.
     */
    public void stop() {
        loop.stop();
    }

    // Package-private: lifecycle specs stop the loop and await its thread before they assert.
    void stopAndJoin() {
        loop.stopAndJoin();
    }

    // Package-private: the policy spec drives this directly, with no thread and no real sleeping.
    void tick() {
        lastRunAt = clock.instant();
        if (!Files.isDirectory(cloneWorktrees)) {
            return;
        }
        Set<String> held = heldEnvironmentKeys();
        Instant now = clock.instant();
        try (Stream<Path> children = Files.list(cloneWorktrees)) {
            children.filter(Files::isDirectory).forEach(dir -> disposeIfAged(dir, held, now));
        } catch (IOException e) {
            log.warn(
                    OperatorEvent.WORKTREE_JANITOR_SCAN_FAILED.head() + "worktree janitor: failed to scan {}",
                    cloneWorktrees,
                    e);
        }
    }

    /**
     * The last time a tick ran (whether or not the clone's worktree folder existed yet), or the
     * construction instant if it never has. Implements FR7 of add-serve-observability.
     *
     * @return the last tick instant; never null
     */
    public Instant lastRunAt() {
        return lastRunAt;
    }

    private void disposeIfAged(Path dir, Set<String> held, Instant now) {
        String key = dir.getFileName().toString();
        if (held.contains(key)) {
            return;
        }
        Duration age = Duration.between(WorktreeActivity.lastActivity(dir), now);
        if (age.compareTo(ageThreshold) < 0) {
            return;
        }
        // FR8/UX2: the loop has no task scope; the key IS the sanitized task id, and the
        // disposal's own lines inherit this one.
        try (var ignored = MdcAwareThread.taskScope(key)) {
            log.info("worktree janitor: disposing aged environment {} (age {})", key, age);
            disposal.dispose(key);
        }
    }

    private Set<String> heldEnvironmentKeys() {
        Set<String> keys = new HashSet<>();
        for (TaskRef ref : heldRefs.occupiedRefs()) {
            try {
                keys.add(TaskIdSanitizer.sanitize(ref.id()));
            } catch (InvalidTaskIdException e) {
                // A held ref that already survived worktree creation is expected to sanitize
                // cleanly; ignored defensively rather than failing the whole tick over one ref.
                try (var ignored = MdcAwareThread.taskScope(ref.id())) {
                    log.warn(
                            OperatorEvent.WORKTREE_JANITOR_REF_UNSANITARY.head()
                                    + "worktree janitor: held ref {} did not sanitize; skipping",
                            ref.id(),
                            e);
                }
            }
        }
        return keys;
    }
}
