package com.github.oinsio.gnomish.logtext;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * The capture/apply/clear pattern for a thread hop that logs (FR8, design D10 of
 * harden-logging-observability). The MDC is thread-local by nature, so a line emitted from a
 * helper thread — a stdout gobbler, an exec pipe drain, a container file pump — lands with an
 * empty context unless the spawning thread's map is carried across explicitly. Without it, the
 * lines that describe what a task's process actually said are exactly the ones a
 * {@code grep taskId=} misses.
 *
 * <p>Wrap the body at the point of <em>construction</em>, on the thread that owns the context:
 *
 * <pre>{@code
 * Thread pump = Thread.ofVirtual()
 *         .name("container-file-pump")
 *         .unstarted(MdcAwareThread.inheritingContext(this::pump));
 * }</pre>
 *
 * <p>The clear is unconditional and runs in a {@code finally}, so a pooled or reused carrier
 * thread never inherits a finished task's context — the leak this helper exists to prevent is as
 * much about what the <em>next</em> body sees as about what this one carries.
 *
 * <p>Implements FR8 of harden-logging-observability.
 */
public final class MdcAwareThread {

    /**
     * The MDC key naming a daemon worker, referenced by the log pattern in
     * {@code logback-spring.xml} as {@code %X{component}}.
     */
    public static final String COMPONENT_KEY = "component";

    /**
     * The MDC key naming the task a line belongs to, referenced by the log pattern as
     * {@code %X{taskId}} and the key every {@code grep taskId=<id>} reconstruction reads.
     */
    public static final String TASK_ID_KEY = "taskId";

    private MdcAwareThread() {}

    /**
     * Scopes the calling thread's lines to {@code taskId} for the duration of a try-with-resources
     * block. This is the form a daemon worker uses for the per-task slice of an otherwise
     * task-less loop — a reaper repairing one stale claim, a janitor removing one task's worktree
     * — so those decisions are findable by the same {@code grep taskId=<id>} that reconstructs the
     * run itself (FR8, UX2 of harden-logging-observability).
     *
     * @param taskId the task the block's lines are about; never null
     * @return the closeable restoring the previous value of the key; never null
     */
    public static MDC.MDCCloseable taskScope(String taskId) {
        return MDC.putCloseable(TASK_ID_KEY, taskId);
    }

    /**
     * Wraps {@code body} so it runs under the calling thread's MDC and leaves no context behind.
     * The context is copied <b>now</b>, on the calling thread — calling this from inside the new
     * thread would capture nothing.
     *
     * @param body the work the new thread will run; never null
     * @return the same work, framed by the context copy and its clear; never null
     */
    public static Runnable inheritingContext(Runnable body) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> runWith(captured, body);
    }

    /**
     * Wraps {@code body} so every line it emits carries {@code component=<name>} and no context
     * survives it. This is the daemon-worker form: a janitor, reaper, snapshot writer, sweep tick
     * or heartbeat runs for the process's whole life and its lines belong to no single task, so
     * without a component key an operator reading the file cannot tell whose voice a line is.
     *
     * <p>Unlike {@link #inheritingContext}, nothing is captured from the caller — a daemon worker
     * deliberately starts from an empty context rather than inheriting whatever the thread that
     * happened to start it was working on.
     *
     * @param component the worker's name, as it will appear in the log pattern; never null
     * @param body the daemon's loop; never null
     * @return the same work, framed by the component key and its clear; never null
     */
    public static Runnable asComponent(String component, Runnable body) {
        return () -> runWith(Map.of(COMPONENT_KEY, component), body);
    }

    /** The framed body: apply what was captured, run, clear unconditionally. */
    private static void runWith(@Nullable Map<String, String> captured, Runnable body) {
        if (captured != null) {
            MDC.setContextMap(captured);
        }
        try {
            body.run();
        } finally {
            MDC.clear();
        }
    }
}
