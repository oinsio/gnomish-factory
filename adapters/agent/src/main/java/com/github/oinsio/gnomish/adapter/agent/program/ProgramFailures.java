package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * The {@code failed/infrastructure} results the factory itself reports for a {@code program} run
 * that produced no readable result (FR2, FR6, NFR-R2 of define-executor-contract): an expired bound,
 * a process the environment would not start, a missing or malformed result file. The executor
 * adapter reports outcomes as results, never as exceptions ({@code Executor}), and decides no class
 * beyond saying the run could not deliver one; the engine's classifier does the rest. The reason is
 * the factory's sentence, naming the cause and, where the process wrote one, quoting the tail of its
 * stderr through the log exit.
 *
 * <p>Implements FR2, FR6, NFR-R2 of define-executor-contract.
 */
final class ProgramFailures {

    /** How much of the stderr tail a failure's reason quotes. */
    static final int TAIL_QUOTE_CHARS = 2048;

    private ProgramFailures() {}

    /**
     * A run that delivered no result.
     *
     * @param executor the executor's name; never blank
     * @param cause what went wrong, the factory's own words; never blank
     * @param stderrTail the tail of the process's stderr, possibly empty; never null
     * @return the result; never null
     */
    static ExecutorResult infrastructure(String executor, String cause, UntrustedText stderrTail) {
        String reason = "program executor '" + executor + "': " + cause;
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
     * @param executor the executor's name; never blank
     * @param cause why the result is refused, the factory's own words; never blank
     * @param discarded the result refused; never null
     * @return the refusal; never null
     */
    static ExecutorResult discarded(String executor, String cause, ExecutorResult discarded) {
        return new ExecutorResult(
                Status.FAILED,
                FailureClass.INFRASTRUCTURE,
                null,
                UntrustedText.factory("program executor '" + executor + "': " + cause),
                null,
                discarded.usage(),
                discarded.denials());
    }
}
