package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.logtext.MdcAwareThread;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.status.DaemonComponent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single worktree cleaner component (design D10, FR14): a virtual thread that, at {@code
 * serve} startup and thereafter every hour, scans this instance's worktrees root for task
 * environments and {@link TaskEnvironmentDisposal#dispose disposes} of every one whose most recent
 * file activity is older than the configured age threshold — except any environment currently
 * occupying a slot of THIS instance ({@code neverTouches}), which is skipped unconditionally
 * regardless of age. There is no separate "is this task ended" check against the tracker
 * (delivered/escalated/revoked, FR14's three named categories all stop touching their worktree
 * once reached): age since last activity, combined with "not currently held here", is the whole
 * policy — deliberately simple, since a disposed-too-early worktree only costs a re-clone on
 * resume (design D10 risk note), never correctness. Worktrees are instance-local, so no
 * cross-instance coordination is needed or attempted.
 *
 * <p>Held tasks are read fresh on every tick via {@code heldRefs}, typically {@code
 * SlotLedger::occupiedRefs} — never a snapshot taken once at construction, since a task claimed
 * after the janitor started must still be protected.
 *
 * <p>Implements FR14 of add-factory-serve (design D10).
 *
 * <p>Kept in sync with {@link SandboxLifecycleTick}: both must keep the same
 * immediate-then-cadence daemon shape — {@code start()} framing a {@code loop()} of {@code
 * while (true) { try tick(); catch RuntimeException log.warn and retry next tick } sleeper.sleep(interval)},
 * with a volatile {@code lastRunAt} stamped after every completed tick.
 */
public final class WorktreeJanitor {

    private static final Logger log = LoggerFactory.getLogger(WorktreeJanitor.class);

    /** The fixed recurring cadence after the immediate startup tick (design D10). */
    static final Duration TICK_INTERVAL = Duration.ofHours(1);

    private final Path worktreesRoot;
    private final Path cloneDir;
    private final Duration ageThreshold;
    private final TaskEnvironmentDisposal disposal;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Supplier<Set<TaskRef>> heldRefs;
    private volatile Instant lastRunAt;

    /**
     * @param worktreesRoot the root directory under which {@code <project-name>/<key>/} worktrees
     *     are created (design D6)
     * @param cloneDir the {@code --dir} project clone; only its file name is used, to name the
     *     project folder under {@code worktreesRoot}
     * @param ageThreshold the minimum time since an environment's last file activity before it is
     *     eligible for disposal ({@code factory.serve.worktree-age-threshold}, design D10)
     * @param disposal the dispose-shaped seam an eligible environment's key is handed to
     * @param clock the source of "now" the age comparison reads
     * @param sleeper the tick-interval sleeper (virtual under test)
     * @param heldRefs supplies, fresh on every tick, the tasks currently occupying a slot of this
     *     instance — never disposed regardless of age
     */
    public WorktreeJanitor(
            Path worktreesRoot,
            Path cloneDir,
            Duration ageThreshold,
            TaskEnvironmentDisposal disposal,
            Clock clock,
            Sleeper sleeper,
            Supplier<Set<TaskRef>> heldRefs) {
        this.worktreesRoot = worktreesRoot;
        this.cloneDir = cloneDir;
        this.ageThreshold = ageThreshold;
        this.disposal = disposal;
        this.clock = clock;
        this.sleeper = sleeper;
        this.heldRefs = heldRefs;
        this.lastRunAt = clock.now();
    }

    /**
     * Starts the janitor thread: one immediate tick (the startup scan, FR14) followed by a tick
     * every {@link #TICK_INTERVAL} thereafter, for the daemon's whole lifetime. A tick failure is
     * logged and never kills the thread — the next tick, an hour later, tries again.
     */
    public void start() {
        Thread.ofVirtual().name("gnomish-worktree-janitor").start(DaemonComponent.JANITOR.framing(this::loop));
    }

    // Package-private: lifecycle specs drive this on their own thread with a controllable sleeper.
    void loop() {
        while (true) {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn(
                        OperatorEvent.WORKTREE_JANITOR_TICK_FAILED.head()
                                + "worktree janitor tick failed; will retry next tick",
                        e);
            }
            sleeper.sleep(TICK_INTERVAL);
        }
    }

    // Package-private: the policy spec drives this directly, with no thread and no real sleeping.
    void tick() {
        Path projectRoot = worktreesRoot.resolve(projectName());
        lastRunAt = clock.now();
        if (!Files.isDirectory(projectRoot)) {
            return;
        }
        Set<String> held = heldEnvironmentKeys();
        Instant now = clock.now();
        try (Stream<Path> children = Files.list(projectRoot)) {
            children.filter(Files::isDirectory).forEach(dir -> disposeIfAged(dir, held, now));
        } catch (IOException e) {
            log.warn(
                    OperatorEvent.WORKTREE_JANITOR_SCAN_FAILED.head() + "worktree janitor: failed to scan {}",
                    projectRoot,
                    e);
        }
    }

    /**
     * The last time a tick completed (whether or not the project's worktree directory existed
     * yet), or this janitor's construction instant if it has never ticked (task 2.5,
     * add-serve-observability FR7).
     *
     * <p>Implements FR7 of add-serve-observability.
     *
     * @return the last completed-tick instant; never null
     */
    public Instant lastRunAt() {
        return lastRunAt;
    }

    private void disposeIfAged(Path dir, Set<String> held, Instant now) {
        String key = dir.getFileName().toString();
        if (held.contains(key)) {
            return;
        }
        Duration age = Duration.between(lastActivity(dir), now);
        if (age.compareTo(ageThreshold) < 0) {
            return;
        }
        // FR8/UX2: the loop has no task scope; the key IS the sanitized task id, and the
        // disposal's own lines inherit this one.
        try (var taskScope = MdcAwareThread.taskScope(key)) {
            log.info("worktree janitor: disposing aged environment {} (age {})", key, age);
            disposal.dispose(key);
        }
    }

    private Set<String> heldEnvironmentKeys() {
        Set<String> keys = new HashSet<>();
        for (TaskRef ref : heldRefs.get()) {
            try {
                keys.add(TaskIdSanitizer.sanitize(ref.id()));
            } catch (InvalidTaskIdException e) {
                // A held ref that already survived worktree creation is expected to sanitize
                // cleanly; ignored defensively rather than failing the whole tick over one ref.
                try (var taskScope = MdcAwareThread.taskScope(ref.id())) {
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

    private String projectName() {
        return cloneDir.toAbsolutePath().normalize().getFileName().toString();
    }

    /**
     * The instant of the most recently modified regular file anywhere under {@code dir}, or
     * {@code dir}'s own last-modified time if it contains none — task activity (writes under
     * {@code .gnomish-task/}, gnome edits) touches nested files, not necessarily the top-level
     * worktree directory entry itself, so the whole tree is walked rather than reading one
     * directory timestamp.
     */
    private static Instant lastActivity(Path dir) {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile)
                    .map(WorktreeJanitor::modifiedInstant)
                    .max(Instant::compareTo)
                    .orElseGet(() -> modifiedInstant(dir));
        } catch (IOException e) {
            throw new UncheckedIOException("worktree janitor: failed to read " + dir, e);
        }
    }

    private static Instant modifiedInstant(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException("worktree janitor: failed to stat " + path, e);
        }
    }
}
