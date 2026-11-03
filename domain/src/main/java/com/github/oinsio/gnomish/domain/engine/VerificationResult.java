package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * The outcome of running one attempt's ordered {@code verify} chain: the
 * {@link CheckResult}s of the checks that actually ran, in run order, plus the
 * {@code verdict} {@link ParticipantUsage} entry of every judge vote cast along the way. The chain
 * stops at the first non-{@link Verdict.Pass} check (FR2), so {@code results} may
 * be shorter than the stage's verify list — it holds only the checks that ran,
 * ending with the one that failed.
 *
 * <p>The overall stage verdict (Pass / Fail / CannotVerify) is deliberately not
 * modelled here: it is derivable from {@code results} by the engine (the last
 * result's verdict when the chain stopped early, or Pass when every check passed),
 * so no helper is added. {@code results} is defensively copied and unmodifiable and
 * may be empty — a stage with no checks passes vacuously. {@code votes} holds one
 * entry per cast judge vote, in vote order across the whole chain, each carrying its
 * index (design D14 of define-executor-contract); it is empty when no judge ran.
 * Both lists are defensively copied. Inert value data compared by content.
 *
 * <p>Implements FR2, FR3 of add-stage-engine; FR15 of define-executor-contract.
 *
 * @param results the per-check results in run order; defensively copied,
 *     unmodifiable, possibly empty
 * @param votes the judge votes' participant entries in vote order; possibly empty
 */
public record VerificationResult(List<CheckResult> results, List<ParticipantUsage> votes) {

    public VerificationResult {
        results = List.copyOf(results);
        votes = List.copyOf(votes);
    }
}
