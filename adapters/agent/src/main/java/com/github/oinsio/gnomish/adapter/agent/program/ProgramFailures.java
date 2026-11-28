package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * The {@code failed/infrastructure} results the factory itself reports for a run that produced no
 * readable result (FR2, FR6, NFR-R2 of define-executor-contract; FR1, FR8 of
 * extract-agent-round-runner): an expired bound, a process the environment would not start, a missing
 * or malformed result file, an answer the working-copy check refuses. The executor adapter reports
 * outcomes as results, never as exceptions ({@code Executor}), and decides no class beyond saying the
 * run could not deliver one; the engine's classifier does the rest. The reason is the factory's
 * sentence, naming the executor by its {@link Subject}, the cause and, where the process wrote one,
 * quoting the tail of its stderr through the log exit. A {@code program} run and an agent round are
 * reported alike, by this one class.
 *
 * <p>Implements FR2, FR6, NFR-R2 of define-executor-contract; FR1, FR8 of extract-agent-round-runner.
 */
public final class ProgramFailures {

    /** How much of the stderr tail a failure's reason quotes. */
    static final int TAIL_QUOTE_CHARS = 2048;

    /** What kind of executor a reason names: the noun before the executor's name. */
    public enum Subject {
        /** A law-declared {@code program} executor. */
        PROGRAM("program executor"),
        /** An agent CLI executor run by the agent round runner. */
        AGENT("agent executor");

        private final String noun;

        Subject(String noun) {
            this.noun = noun;
        }

        /** The head of a reason naming {@code executor}: the noun, the name and a colon. */
        String named(String executor) {
            return noun + " '" + executor + "': ";
        }
    }

    private ProgramFailures() {}

    /**
     * A run that delivered no result.
     *
     * @param subject what kind of executor ran; never null
     * @param executor the executor's name; never blank
     * @param cause what went wrong, the factory's own words; never blank
     * @param stderrTail the tail of the process's stderr, possibly empty; never null
     * @return the result; never null
     */
    public static ExecutorResult infrastructure(
            Subject subject, String executor, String cause, UntrustedText stderrTail) {
        String reason = subject.named(executor) + cause;
        if (!stderrTail.isBlank()) {
            reason += "; its stderr ended with: " + stderrTail.excerpt(TAIL_QUOTE_CHARS);
        }
        return new ExecutorResult(
                Status.FAILED,
                FailureClass.INFRASTRUCTURE,
                null,
                UntrustedText.factory(reason),
                null,
                Usage.absent(),
                List.of());
    }

    /**
     * A run whose result the factory refuses after the fact: {@code failed/infrastructure} in the
     * factory's words, its answer discarded, the usage and denials it reported kept, since they were
     * spent and met whatever the factory makes of the answer.
     *
     * @param subject what kind of executor ran; never null
     * @param executor the executor's name; never blank
     * @param cause why the result is refused, the factory's own words; never blank
     * @param discarded the result refused; never null
     * @return the refusal; never null
     */
    public static ExecutorResult discarded(Subject subject, String executor, String cause, ExecutorResult discarded) {
        return new ExecutorResult(
                Status.FAILED,
                FailureClass.INFRASTRUCTURE,
                null,
                UntrustedText.factory(subject.named(executor) + cause),
                null,
                discarded.usage(),
                discarded.denials());
    }
}
