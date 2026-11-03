package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What an {@code agent-cli} round that ended without its result becomes (FR7 of
 * define-executor-contract, design D10): a {@code failed/infrastructure} result reported to the
 * engine, never a throw. A {@code roundTimeout} kill, an interrupted wait, a process that would not
 * start, a missing result event, a dead egress guard, a close that could not commit — each is the
 * contract status the engine's classifier takes, so the adapter decides no escalation of its own.
 *
 * <p>The result carries what the round spent — its wall time, from the launch sequence's start —
 * and the denials drained from its environment (FR1 of fix-denial-attribution-durability). Its
 * reason names the failure and every cause under it, then the tail of the CLI's stderr through the
 * log exit (FR20 of fix-terminal-receipt-convergence); the stack goes to one DEBUG line, since the
 * engine writes the round's one operator line (logging.md, "One failure, one log").
 *
 * <p>An interrupt is not swallowed: the handle that saw it left the thread's interrupt flag set
 * ({@code ProcessSupervisor}), and nothing here reads or clears it.
 *
 * <p>Implements FR7 of define-executor-contract; FR1 of fix-denial-attribution-durability; FR20 of
 * fix-terminal-receipt-convergence.
 */
final class AgentRoundFailure {

    private static final Logger log = LoggerFactory.getLogger(AgentRoundFailure.class);

    /** What the stderr tail is, spelled once for the reason. */
    static final String STDERR_TAIL_LABEL = "stderr of the round's process, last bytes: ";

    /** How many causes under the failure the reason names: a cyclic chain must not loop. */
    static final int MAX_CAUSES = 8;

    private AgentRoundFailure() {}

    /**
     * The round's {@code failed/infrastructure} result.
     *
     * @param key the round's correlation key; never null
     * @param failure what ended the round; never null
     * @param stderrTail the tail of the CLI's stderr; never null, blank when no process ran or it
     *     wrote none
     * @param wallTime how long the round ran; never null, non-negative
     * @param denials the egress denials drained from the round's environment; never null
     * @return the result; never null
     */
    static ExecutionResult of(
            AttemptKey key,
            RuntimeException failure,
            UntrustedText stderrTail,
            Duration wallTime,
            List<Denial> denials) {
        // throwable-not-subject: the round's failed/infrastructure carries the failure's words; the
        //     engine writes the one operator line for it, and this line keeps the stack.
        log.debug("agent-cli round {} ended without its result", key, failure);
        var status = new ExecutionResult.Failed(
                ExecutionResult.FailureClass.INFRASTRUCTURE, reason(failure, stderrTail), List.of());
        return new ExecutionResult(
                status, null, new ExecutorUsage(wallTime, List.of(), Map.of()), new ToolTrace(key, List.of()), denials);
    }

    /**
     * The time between two readings of the round's clock, never negative: a clock that stepped back
     * spent no time.
     *
     * @param from the reading at the round's start; never null
     * @param to the reading at its end; never null
     * @return the elapsed time, or zero; never null
     */
    static Duration elapsed(Instant from, Instant to) {
        Duration elapsed = Duration.between(from, to);
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }

    /** The failure, its causes, then the stderr tail through the log exit when there is one. */
    static UntrustedText reason(RuntimeException failure, UntrustedText stderrTail) {
        var text = new StringBuilder("agent-cli round ended without its result: ").append(failure);
        // A bounded stream, not a counted loop: a mutated counter on a cyclic chain never ends and
        //     kills the PIT minion (MEMORY_ERROR) instead of failing a spec.
        Stream.<@Nullable Throwable>iterate(failure.getCause(), Objects::nonNull, AgentRoundFailure::causeOf)
                .limit(MAX_CAUSES)
                .forEach(cause -> text.append("; caused by: ").append(cause));
        if (!stderrTail.isBlank()) {
            text.append("; ").append(STDERR_TAIL_LABEL).append(stderrTail.forLog());
        }
        return UntrustedText.subprocess(text.toString());
    }

    /** The next link of a cause chain; only called on a link the stream checked is present. */
    private static @Nullable Throwable causeOf(@Nullable Throwable link) {
        return Objects.requireNonNull(link).getCause();
    }
}
