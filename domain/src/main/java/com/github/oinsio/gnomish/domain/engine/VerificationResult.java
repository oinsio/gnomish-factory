package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * The outcome of running one attempt's ordered {@code verify} chain: the
 * {@link CheckResult}s of the checks that actually ran, in run order, plus the
 * {@link JudgeUsage} accounting for any judge votes cast along the way. The chain
 * stops at the first non-{@link Verdict.Pass} check (FR2), so {@code results} may
 * be shorter than the stage's verify list — it holds only the checks that ran,
 * ending with the one that failed.
 *
 * <p>The overall stage verdict (Pass / Fail / CannotVerify) is deliberately not
 * modelled here: it is derivable from {@code results} by the engine (the last
 * result's verdict when the chain stopped early, or Pass when every check passed),
 * so no helper is added. {@code results} is defensively copied and unmodifiable and
 * may be empty — a stage with no checks passes vacuously. {@code judgeUsage} is
 * required non-null and holds one {@link TokenUsage} per cast judge vote that
 * reported tokens (design D5, NFR-C1); it is {@link JudgeUsage#none()}-equivalent
 * when no judge ran. Inert value data compared by content.
 *
 * <p>Implements FR2, FR3 of add-stage-engine.
 *
 * @param results the per-check results in run order; defensively copied,
 *     unmodifiable, possibly empty
 * @param judgeUsage the per-vote judge token accounting; never null, possibly empty
 */
public record VerificationResult(List<CheckResult> results, JudgeUsage judgeUsage) {

    public VerificationResult {
        results = List.copyOf(results);
    }
}
