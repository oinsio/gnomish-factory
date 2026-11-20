package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.Threshold;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The one place a verify check's {@link Verdict} is derived from what its executor answered (design
 * D5, D9 of migrate-verify-to-executor-contract): an executor reports status and a SARIF answer
 * only, and pass or fail is decided here, from the answer's result levels against the check's
 * {@link Threshold}. No executor constructs a verdict.
 *
 * <ul>
 *   <li>A {@link VerdictAnswer.Sarif} answer fails when any result {@linkplain
 *       ResultLevel#reaches reaches} the threshold; the failure carries every result as a finding,
 *       in the order written, the ones below the threshold included. It passes otherwise, carrying
 *       the answer's platform run URL and every result as a finding, in the order written — all
 *       of them below the threshold (design D5 as amended).
 *   <li>A {@link VerdictAnswer.Malformed} answer is {@link Verdict.CannotVerify}, never a pass: the
 *       reason names the malformed answer and the details quote what the reading refused.
 *   <li>A {@link VerdictAnswer.QualityFailure} is {@link Verdict.Fail} with its findings, and a
 *       {@link VerdictAnswer.InfrastructureFailure} is {@link Verdict.CannotVerify} with its reason
 *       and details, under every threshold: a failed status has no levels to judge.
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
            case VerdictAnswer.Sarif(var results, var runUrl) -> judge(results, threshold, runUrl);
            case VerdictAnswer.Malformed(var reason) ->
                new Verdict.CannotVerify(UntrustedText.factory("the verdict answer is malformed"), reason);
            case VerdictAnswer.QualityFailure(var findings) -> new Verdict.Fail(findings);
            case VerdictAnswer.InfrastructureFailure(var reason, var details) ->
                new Verdict.CannotVerify(reason, details);
        };
    }

    private static Verdict judge(List<VerdictAnswer.Result> results, Threshold threshold, @Nullable String runUrl) {
        boolean fails = false;
        List<Finding> findings = new ArrayList<>(results.size());
        for (VerdictAnswer.Result result : results) {
            fails |= result.level().reaches(threshold);
            findings.add(result.finding());
        }
        return fails ? new Verdict.Fail(findings) : new Verdict.Pass(runUrl, findings);
    }
}
