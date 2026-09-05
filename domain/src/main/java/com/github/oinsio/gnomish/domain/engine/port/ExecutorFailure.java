package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.Denial;
import java.io.Serial;
import java.util.List;

/**
 * The infrastructure failure of one executor round, carrying the egress denials the round's
 * environment recorded before it died. A {@link StageExecutor} that has an execution
 * environment throws this instead of the bare failure when its round failed before its close
 * — a {@code roundTimeout} kill, a missing result event, a process that would not start — so
 * the denials the round earned travel out with the failure rather than only into the factory
 * log (FR1 of fix-denial-attribution-durability, design D1).
 *
 * <p>The original failure is the {@link #getCause() cause} and stays the diagnosable one: the
 * engine renders the <em>cause</em> into the escalation, so the escalation text of a failure
 * with denials is identical to the same failure without them. This wrapper adds attribution,
 * never a new failure mode.
 *
 * <p>Any other {@link RuntimeException} an executor throws keeps today's behaviour — the
 * engine still shapes it into a {@code CannotExecute} escalation, with an empty denials list
 * — so an executor adapter that has no environment needs no change (design D1).
 *
 * <p>{@code denials} is defensively copied and unmodifiable; it may be empty (a round that
 * failed with nothing blocked), in which case throwing this type is equivalent to throwing
 * the cause itself.
 *
 * <p>Implements FR1 of fix-denial-attribution-durability.
 */
public final class ExecutorFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Throwable failure;
    private final transient List<Denial> denials;

    /**
     * Wraps {@code cause} with the denials drained from the failed round's environment. The
     * message is left to the cause — this type contributes no text of its own, because the
     * engine renders the cause and the escalation text must stay unchanged (design D1).
     *
     * @param cause the original infrastructure failure; never null
     * @param denials the egress denials drained from the round that failed; defensively
     *     copied, unmodifiable, possibly empty
     */
    public ExecutorFailure(Throwable cause, List<Denial> denials) {
        super(cause);
        this.failure = cause;
        this.denials = List.copyOf(denials);
    }

    /**
     * The original infrastructure failure this wraps — the same object {@link #getCause()}
     * returns, re-declared here as never-null so the engine can render it without a nullness
     * check that could never fire: an {@code ExecutorFailure} without a cause cannot be
     * constructed.
     *
     * @return the wrapped failure; never null
     */
    public Throwable cause() {
        return failure;
    }

    /**
     * The egress denials the failed round's environment recorded, in read order; unmodifiable
     * and possibly empty. Carried, never consulted: they change no outcome classification
     * (FR1).
     *
     * @return the round's denials; never null, possibly empty
     */
    public List<Denial> denials() {
        return denials;
    }
}
