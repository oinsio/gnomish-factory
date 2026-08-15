package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.Map;

/**
 * The port through which the engine casts one LLM-as-judge vote against a stage's
 * acceptance criteria without knowing which model or provider grades it (design
 * D2): the engine hands over a {@link VerifyCheck.Judge}, the task's {@link
 * TaskContext}, and the opaque {@link Workspace}, and reads back a single {@link
 * Vote}. Loading the criteria file, calling the model, and parsing its structured
 * verdict are the adapter's concern.
 *
 * <p>This port casts a <em>single</em> vote — the engine owns the majority loop
 * (it calls {@link #vote} up to {@code check.votes()} times and tallies the
 * verdicts, task 4.4). The {@link TaskContext} is passed through so the human
 * decisions recorded on the task reach the judge's prompt (FR7).
 *
 * <p>Implements FR3, D2 of add-stage-engine.
 */
public interface JudgeVoter {

    /**
     * Casts one judge vote for {@code check} and returns its verdict together with
     * the tokens it consumed — {@link Verdict.Pass} or {@link Verdict.Fail} when
     * the judge graded the artifact, or {@link Verdict.CannotVerify} when no
     * verdict could be obtained (e.g. unparseable judge output). The engine never
     * inspects the workspace; it belongs to the adapter.
     *
     * <p>Implements FR3, D2 of add-stage-engine.
     *
     * @param check the judge check whose criteria and model settings drive the vote
     * @param context the task's identity and human decisions, threaded into the
     *     judge prompt (FR7)
     * @param workspace the opaque working copy being graded
     * @return the vote's verdict and token usage; never null
     */
    Vote vote(VerifyCheck.Judge check, TaskContext context, Workspace workspace);

    /**
     * The outcome of one judge vote: the graded {@link Verdict} and the per-model
     * {@link TokenUsage} map the vote consumed. The engine tallies these into a
     * majority verdict and aggregates the per-vote token maps for cost telemetry
     * (NFR-C1). Inert value data compared by content.
     *
     * <p>{@code tokensByModel} is map-only, following {@link
     * com.github.oinsio.gnomish.domain.engine.ExecutorUsage#tokensByModel()}'s
     * shape (design D4): an empty map means the adapter did not report token
     * counts for this vote — the interactive judge always reports an empty map,
     * since a human vote never carries tokens — and the engine simply omits an
     * unreported vote from its cost aggregation rather than treating a missing
     * count as zero.
     *
     * <p>Implements FR3, D2 of add-stage-engine; FR9, NFR-C1, D4 of add-agent-executor.
     *
     * @param verdict this vote's graded verdict; never null
     * @param tokensByModel the tokens this vote consumed, keyed by resolved model
     *     id; defensively copied, unmodifiable, empty when unreported
     */
    record Vote(Verdict verdict, Map<String, TokenUsage> tokensByModel) {

        public Vote {
            tokensByModel = Map.copyOf(tokensByModel);
        }
    }
}
