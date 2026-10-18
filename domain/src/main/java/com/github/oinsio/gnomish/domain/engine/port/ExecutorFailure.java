package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;
import java.util.List;
import java.util.Objects;

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
 * the cause itself. It is {@code transient} because a {@link Denial} is not serializable: this
 * type travels one stack, from the adapter that throws it to the engine frame that catches it,
 * and a deserialized instance would carry no denials. Do not serialize it.
 *
 * <p>A round that ran a process of its own also hands over the {@link #stderrTail() tail of
 * that process's stderr}: the last bytes the CLI wrote, the one diagnostic of a CLI that failed
 * to start, died without a result or was killed. The engine renders it after the cause, in its
 * ERROR line and in the escalation (design D18 of fix-terminal-receipt-convergence, as amended
 * 2026-10-17); an empty tail renders nothing, so the escalation text of a failure without one is
 * unchanged. Like the denials, it is {@code transient}: it travels the same one stack.
 *
 * <p>Implements FR1 of fix-denial-attribution-durability; FR20 of
 * fix-terminal-receipt-convergence.
 */
public final class ExecutorFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The one spelling of what the stderr tail is, in this message, the report and the log line alike. */
    public static final String STDERR_TAIL_LABEL = "stderr of the round's process, last bytes: ";

    private final transient List<Denial> denials;

    private final transient UntrustedText stderrTail;

    /**
     * Wraps {@code cause} with the denials drained from the failed round's environment. The
     * message is the cause's — this form contributes no text of its own, because the engine
     * renders the cause and the escalation text must stay unchanged (design D1).
     *
     * @param cause the original infrastructure failure; never null
     * @param denials the egress denials drained from the round that failed; defensively
     *     copied, unmodifiable, possibly empty
     */
    // The form for an executor that runs no process of its own: an empty stderr tail.
    public ExecutorFailure(Throwable cause, List<Denial> denials) {
        this(cause, denials, UntrustedText.factory(""));
    }

    /**
     * Wraps {@code cause} with the denials and the stderr tail of the failed round's process. The
     * message is the cause's, followed by the tail through its log exit when there is one
     * (design D5 of type-untrusted-text: a throwable that carries a machine's words composes its
     * message through the carrier's exit), so a log of this wrapper itself is inert too; the
     * engine renders the cause and the tail apart and never reads this message.
     *
     * @param cause the original infrastructure failure; never null
     * @param denials the egress denials drained from the round that failed; defensively
     *     copied, unmodifiable, possibly empty
     * @param stderrTail the last bytes the round's process wrote to its stderr, as captured;
     *     never null, empty when it wrote none or never started
     */
    public ExecutorFailure(Throwable cause, List<Denial> denials, UntrustedText stderrTail) {
        super(cause.toString() + tailSuffix(stderrTail), cause);
        this.denials = List.copyOf(denials);
        this.stderrTail = stderrTail;
    }

    /**
     * The original infrastructure failure this wraps — {@link #getCause()} narrowed to
     * never-null, so the engine can render it without a nullness check that could never fire:
     * the constructor's {@code cause} is non-null under JSpecify and is the only way an
     * {@code ExecutorFailure} comes into being.
     *
     * @return the wrapped failure; never null
     */
    public Throwable cause() {
        return Objects.requireNonNull(getCause());
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

    /** {@code "; "}, the label and the tail through its log exit; empty for a blank tail. */
    private static String tailSuffix(UntrustedText stderrTail) {
        return stderrTail.isBlank() ? "" : "; " + STDERR_TAIL_LABEL + stderrTail.forLog();
    }

    /**
     * The tail of the failed round's process stderr; carried to the engine's renderings, never
     * consulted for an outcome (FR20 of fix-terminal-receipt-convergence).
     *
     * @return the captured tail; never null, blank when there is none
     */
    public UntrustedText stderrTail() {
        return stderrTail;
    }
}
