package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Verdict
import spock.lang.Specification

/**
 * Shared abstract port contract for the two synchronous check runners —
 * {@link com.github.oinsio.gnomish.domain.engine.port.BuiltinCheckRunner} and
 * {@link com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner} — which
 * differ only in the {@code VerifyCheck} variant they accept but share the
 * {@link Verdict} return shape and every obligation the engine depends on. A
 * concrete subclass binds its runner through the {@link #arrange} seam, which both
 * builds the runner for a target verdict AND runs the check against a workspace,
 * returning the produced {@link Verdict}; an unproducible variant returns
 * {@link Optional#empty} and the row is recorded and skipped.
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
     * The arrangement seam: build the runner-under-test so its check yields the
     * given {@code variant}, run that check against the runner's workspace, and
     * return the produced {@link Verdict}; or return {@link Optional#empty} to
     * declare the variant unproducible.
     *
     * @param variant which verdict the row needs the runner to produce
     * @return the produced verdict, or empty when unproducible
     */
    protected abstract Optional<Verdict> arrange(VerdictVariant variant)

    /** The port name used in recorded port-shape findings (subclass identifies its port). */
    protected abstract String portName()

    // FR14: run returns a non-null Verdict of the declared sealed type (FR3)
    def "run returns a non-null Verdict"() {
        given: 'a runner arranged to pass'
        def verdict = arrange(VerdictVariant.PASS)
        assumeProducible(verdict, portName(), 'Pass')

        expect: 'a non-null sealed Verdict comes back'
        verdict.get() != null
        verdict.get() instanceof Verdict
    }

    // FR14: the Pass variant is reachable (FR4)
    def "run can yield Pass"() {
        given: 'a runner arranged to pass'
        def verdict = arrange(VerdictVariant.PASS)
        assumeProducible(verdict, portName(), 'Pass')

        expect: 'it is a Pass'
        verdict.get() instanceof Verdict.Pass
    }

    // FR14: the Fail variant is reachable and carries an unmodifiable findings list (FR4)
    def "run can yield Fail carrying an unmodifiable findings list"() {
        given: 'a runner arranged to fail with findings'
        def verdict = arrange(VerdictVariant.FAIL_WITH_FINDINGS)
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
        def verdict = arrange(VerdictVariant.CANNOT_VERIFY)
        assumeProducible(verdict, portName(), 'CannotVerify')

        expect: 'it is a CannotVerify whose reason the escalation report can name'
        verdict.get() instanceof Verdict.CannotVerify
        !verdict.get().reason().isBlank()
        verdict.get().details() != null
    }
}
