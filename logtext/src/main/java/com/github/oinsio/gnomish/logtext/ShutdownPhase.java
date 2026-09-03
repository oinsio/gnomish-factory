package com.github.oinsio.gnomish.logtext;

/**
 * The process-global "we are stopping on purpose" flag (design D6 of
 * harden-logging-observability). A stop kills gnome subprocesses and interrupts daemon workers;
 * every one of those deaths looks exactly like the spontaneous failure the same code path reports
 * as an alarm. Without a way to tell the two apart, an ordinary SIGTERM ends a healthy daemon's
 * log with a burst of ERROR lines that name no fault — the noise that trains an operator to
 * ignore the level that matters.
 *
 * <p>The flag is set once, first thing in the shutdown hook that owns the stop, and read by the
 * three sites that classify a death: the slot crash boundary, the heartbeat worker's
 * uncaught-exception handler, and the subprocess supervisors' bound-fired reports. It is
 * deliberately a static, not an injected collaborator: the readers span the application layer, the
 * git adapter and the sandbox backend — layers with no common owner to thread an instance through
 * — and the fact itself is a property of the JVM, not of any one object graph.
 *
 * <p>One-way by design: there is no "shutdown finished" state, because nothing that reads the flag
 * outlives the stop. {@link #reset()} exists for specs, whose JVM does outlive it.
 *
 * <p>Like the rest of this package this is a primitive, not policy: it answers <em>whether</em> the
 * stop has begun and never picks a level or emits a line.
 *
 * <p>Implements FR9 of harden-logging-observability.
 */
public final class ShutdownPhase {

    private static volatile boolean inProgress;

    private ShutdownPhase() {}

    /**
     * Marks the shutdown phase as begun; idempotent, and safe to call from the shutdown-hook
     * thread while the readers below run on their own threads (the field is {@code volatile}).
     */
    public static void begin() {
        inProgress = true;
    }

    /**
     * @return {@code true} once {@link #begin()} has run — the caller's failure is attributable to
     *     the stop rather than to a fault of its own
     */
    public static boolean inProgress() {
        return inProgress;
    }

    /**
     * Clears the flag. Test support only: production has no "not shutting down any more"
     * transition, but a spec JVM runs many stops and each must start from a clean phase.
     */
    public static void reset() {
        inProgress = false;
    }
}
