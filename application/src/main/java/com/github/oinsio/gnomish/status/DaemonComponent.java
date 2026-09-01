package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.logtext.MdcAwareThread;

/**
 * The daemon workers a serve run starts, and the names their log lines carry in the
 * {@code component} MDC key (FR8, design D10 of harden-logging-observability).
 *
 * <p>A serve run's file interleaves five long-lived voices with the task work itself. The task
 * lines are correlated by {@code taskId}; the daemons' are not — a janitor removing an orphaned
 * worktree, a reaper converging someone else's stale claim, a sweep tick grading containers all
 * speak about the estate rather than about one task. Without a key naming the speaker, a
 * post-mortem cannot separate them, and {@code grep component=reaper} is the question an operator
 * actually asks.
 *
 * <p>This enum is the vocabulary's single owner, so the set a spec enumerates and the set the
 * workers use cannot drift. Each worker frames its loop with {@link #framing} at the point it
 * starts its thread; the key is cleared when the loop ends.
 *
 * <p>Implements FR8 of harden-logging-observability.
 */
public enum DaemonComponent {

    /** Removes worktrees the factory no longer owns. */
    JANITOR("janitor"),

    /** Converges stale claims left by instances that died holding them. */
    REAPER("reaper"),

    /** Writes the observability snapshot the dashboard reads. */
    SNAPSHOT("snapshot"),

    /** Grades and disposes execution environments the estate no longer needs. */
    SWEEP("sweep"),

    /** Renews the leases of the claims this instance holds. */
    HEARTBEAT("heartbeat");

    private final String key;

    DaemonComponent(String key) {
        this.key = key;
    }

    /**
     * The name this worker's lines carry.
     *
     * @return the component name as it appears in the log line; never null
     */
    public String key() {
        return key;
    }

    /**
     * Frames a daemon loop so every line it emits names this component and no MDC context outlives
     * it. Call it where the thread is <em>started</em>, so the whole loop is inside the frame.
     *
     * @param body the daemon's loop; never null
     * @return the loop, framed by the component key; never null
     */
    public Runnable framing(Runnable body) {
        return MdcAwareThread.asComponent(key, body);
    }
}
