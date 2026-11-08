package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.VerdictDerivation
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import spock.lang.Specification

/**
 * Shared abstract port contract for the two synchronous check runners —
 * {@link com.github.oinsio.gnomish.domain.engine.port.BuiltinCheckRunner} and
 * {@link com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner} — which
 * differ only in the {@code VerifyCheck} variant they accept but share the
 * {@link VerdictAnswer} return shape and every obligation the engine depends on. A
 * concrete subclass binds its runner through the {@link #arrange} seam, which both
 * builds the runner for a target verdict AND runs the check against a workspace,
 * returning the produced {@link VerdictAnswer}; an unproducible variant returns
 * {@link Optional#empty} and the row is recorded and skipped. The rows judge the
 * {@link Verdict} the engine derives from the answer through {@link VerdictDerivation}
 * under the default threshold — a runner constructs no verdict (D5, D9 of
 * migrate-verify-to-executor-contract).
 *
 * <p>FR14 of add-manual-run: interactive and real adapters pass the same
 * port-contract suites as the fakes (metric M2). Underlying obligations come from
 * FR3/FR4/D2 of add-stage-engine.
 */
abstract class CheckRunnerContract extends Specification implements PortContractSupport {

    /** The {@link Verdict} variants a check runner must be able to yield. */
    enum VerdictVariant {
        PASS, FAIL_WITH_FINDINGS, CANNOT_VERIFY
    }

    /**
     * The arrangement seam: build the runner-under-test so its check's answer derives
     * to the given {@code variant}, run that check against the runner's workspace, and
     * return the produced {@link VerdictAnswer}; or return {@link Optional#empty} to
     * declare the variant unproducible.
     *
     * @param variant which verdict the row needs the runner's answer to derive to
     * @return the produced answer, or empty when unproducible
     */
    protected abstract Optional<VerdictAnswer> arrange(VerdictVariant variant)

    /** The verdict the engine derives from the arranged answer, under the default threshold. */
    private Optional<Verdict> derived(VerdictVariant variant) {
        arrange(variant).map { VerdictDerivation.derive(it, Threshold.DEFAULT) }
    }

    /** The port name used in recorded port-shape findings (subclass identifies its port). */
    protected abstract String portName()

    // FR14: run returns a non-null answer of the declared sealed type (FR3; FR7 of migrate-verify-to-executor-contract)
    def "run returns a non-null VerdictAnswer"() {
        given: 'a runner arranged to pass'
        def answer = arrange(VerdictVariant.PASS)
        assumeProducible(answer, portName(), 'Pass')

        expect: 'a non-null sealed answer comes back, never a verdict'
        answer.get() != null
        answer.get() instanceof VerdictAnswer
    }

    // FR14: the Pass variant is reachable (FR4)
    def "run can yield Pass"() {
        given: 'a runner arranged to pass'
        def verdict = derived(VerdictVariant.PASS)
        assumeProducible(verdict, portName(), 'Pass')

        expect: 'it is a Pass'
        verdict.get() instanceof Verdict.Pass
    }

    // FR14: the Fail variant is reachable and carries an unmodifiable findings list (FR4)
    def "run can yield Fail carrying an unmodifiable findings list"() {
        given: 'a runner arranged to fail with findings'
        def verdict = derived(VerdictVariant.FAIL_WITH_FINDINGS)
        assumeProducible(verdict, portName(), 'Fail')

        expect: 'it is a Fail carrying its findings'
        verdict.get() instanceof Verdict.Fail
        verdict.get().findings() != null

        when: 'a caller tries to mutate the findings'
        verdict.get().findings().add(new Finding('sneaked', null, null))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR14: the CannotVerify variant is reachable with a non-blank reason (FR4, NFR-O1)
    def "run can yield CannotVerify with a non-blank reason"() {
        given: 'a runner arranged so no verdict can be obtained'
        def verdict = derived(VerdictVariant.CANNOT_VERIFY)
        assumeProducible(verdict, portName(), 'CannotVerify')

        expect: 'it is a CannotVerify whose reason the escalation report can name'
        verdict.get() instanceof Verdict.CannotVerify
        !verdict.get().reason().isBlank()
        verdict.get().details() != null
    }
}
