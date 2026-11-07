package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.Threshold;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place a verify check's {@link Verdict} is derived from what its executor answered (design
 * D5, D9 of migrate-verify-to-executor-contract): an executor reports status and a SARIF answer
 * only, and pass or fail is decided here, from the answer's result levels against the check's
 * {@link Threshold}. No executor constructs a verdict.
 *
 * <ul>
 *   <li>A {@link VerdictAnswer.Sarif} answer fails when any result {@linkplain
 *       ResultLevel#reaches reaches} the threshold; the failure carries every result as a finding,
 *       in the order written, the ones below the threshold included. It passes otherwise.
 *   <li>A {@link VerdictAnswer.Malformed} answer is {@link Verdict.CannotVerify}, never a pass: the
 *       reason names the malformed answer and the details quote what the reading refused.
 * </ul>
 *
 * <p>Implements FR7 of migrate-verify-to-executor-contract.
 */
public final class VerdictDerivation {

    private VerdictDerivation() {}

    /**
     * The verdict of a check whose executor gave {@code answer}, judged against {@code threshold}.
     *
     * @param answer what the executor answered; never null
     * @param threshold the check's threshold; never null
     * @return the derived verdict; never null
     */
    public static Verdict derive(VerdictAnswer answer, Threshold threshold) {
        return switch (answer) {
            case VerdictAnswer.Sarif(var results) -> judge(results, threshold);
            case VerdictAnswer.Malformed(var reason) ->
                new Verdict.CannotVerify(UntrustedText.factory("the verdict answer is malformed"), reason);
        };
    }

    private static Verdict judge(List<VerdictAnswer.Result> results, Threshold threshold) {
        boolean fails = false;
        List<Finding> findings = new ArrayList<>(results.size());
        for (VerdictAnswer.Result result : results) {
            fails |= result.level().reaches(threshold);
            findings.add(result.finding());
        }
        return fails ? new Verdict.Fail(findings) : new Verdict.Pass();
    }
}
