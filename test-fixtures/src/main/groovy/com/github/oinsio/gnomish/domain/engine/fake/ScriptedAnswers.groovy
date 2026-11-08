package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer

/**
 * The answer a scripted check runner hands the engine for a scripted entry: a {@link VerdictAnswer}
 * as written, or a {@link Verdict} translated to the answer the engine's {@code VerdictDerivation}
 * reads back as that same verdict under every threshold — a pass as a SARIF answer carrying its run
 * URL and each of its findings as a result of level {@code none}, which reaches no threshold, a fail as a quality failure carrying its findings (an empty list included), a
 * cannot-verify as an infrastructure failure carrying its reason and details. Specs keep scripting
 * the verdict they mean; the runners answer as the port requires (D5, D9 of
 * migrate-verify-to-executor-contract).
 *
 * <p>Test fixture; not production code, never PIT-mutated.
 */
final class ScriptedAnswers {

    private ScriptedAnswers() {}

    static VerdictAnswer of(Object scripted) {
        switch (scripted) {
            case VerdictAnswer:
                return scripted as VerdictAnswer
            case Verdict.Pass:
                def pass = scripted as Verdict.Pass
                def results = pass.findings().collect {
                    new VerdictAnswer.Result(ResultLevel.NONE, it)
                }
                return new VerdictAnswer.Sarif(results, pass.runUrl())
            case Verdict.Fail:
                return new VerdictAnswer.QualityFailure((scripted as Verdict.Fail).findings())
            case Verdict.CannotVerify:
                def cannot = scripted as Verdict.CannotVerify
                return new VerdictAnswer.InfrastructureFailure(cannot.reason(), cannot.details())
            default:
                throw new IllegalArgumentException('not a scripted verdict or answer: ' + scripted)
        }
    }
}
