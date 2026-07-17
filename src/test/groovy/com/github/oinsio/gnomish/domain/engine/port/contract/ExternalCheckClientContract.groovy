package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.PollStatus
import spock.lang.Specification

/**
 * Abstract port contract for
 * {@link com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient}: the
 * behavioural guarantees the engine's poll loop relies on from ANY external-check
 * adapter when it polls once and reads back a single {@link PollStatus}. A concrete
 * subclass binds an adapter-under-test through the {@link #arrange} seam, which
 * builds the client for a target status, polls once, and returns the produced
 * {@link PollStatus}; an unproducible variant returns {@link Optional#empty} and the
 * row is recorded and skipped.
 *
 * <p>FR14 of add-manual-run: interactive and real adapters pass the same
 * port-contract suites as the fakes (metric M2). Underlying obligations come from
 * FR3/D2 of add-stage-engine.
 */
abstract class ExternalCheckClientContract extends Specification implements PortContractSupport {

    /** The four {@link PollStatus} variants the engine's poll loop switches on. */
    enum PollVariant {
        PASS, FAIL_WITH_FINDINGS, RUNNING, CANNOT_VERIFY
    }

    /**
     * The arrangement seam: build the client-under-test so its poll yields the given
     * {@code variant}, poll once, and return the produced {@link PollStatus}; or
     * return {@link Optional#empty} to declare the variant unproducible.
     *
     * @param variant which poll status the row needs
     * @return the produced status, or empty when unproducible
     */
    protected abstract Optional<PollStatus> arrange(PollVariant variant)

    // FR14: poll returns a non-null PollStatus of the declared sealed type (FR3)
    def "poll returns a non-null PollStatus"() {
        given: 'a client arranged to report success'
        def status = arrange(PollVariant.PASS)
        assumeProducible(status, 'ExternalCheckClient', 'Pass')

        expect: 'a non-null sealed PollStatus comes back'
        status.get() != null
        status.get() instanceof PollStatus
    }

    // FR14: the Pass variant is reachable (FR3)
    def "poll can yield Pass"() {
        given: 'a client arranged to report success'
        def status = arrange(PollVariant.PASS)
        assumeProducible(status, 'ExternalCheckClient', 'Pass')

        expect: 'it is a Pass'
        status.get() instanceof PollStatus.Pass
    }

    // FR14: the Fail variant is reachable and carries an unmodifiable findings list (FR3)
    def "poll can yield Fail carrying an unmodifiable findings list"() {
        given: 'a client arranged to report a quality failure'
        def status = arrange(PollVariant.FAIL_WITH_FINDINGS)
        assumeProducible(status, 'ExternalCheckClient', 'Fail')

        expect: 'it is a Fail carrying its findings'
        status.get() instanceof PollStatus.Fail
        status.get().findings() != null

        when: 'a caller tries to mutate the findings'
        status.get().findings().add(new Finding('sneaked', null, null))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR14: the Running variant is reachable — the engine keeps polling until timeout (FR3)
    def "poll can yield Running"() {
        given: 'a client arranged to report work still in progress'
        def status = arrange(PollVariant.RUNNING)
        assumeProducible(status, 'ExternalCheckClient', 'Running')

        expect: 'it is a Running the engine will poll again on'
        status.get() instanceof PollStatus.Running
    }

    // FR14: the CannotVerify variant is reachable with a non-blank reason (FR3, NFR-O1)
    def "poll can yield CannotVerify with a non-blank reason"() {
        given: 'a client arranged so no result can be obtained'
        def status = arrange(PollVariant.CANNOT_VERIFY)
        assumeProducible(status, 'ExternalCheckClient', 'CannotVerify')

        expect: 'it is a CannotVerify whose reason the escalation report can name'
        status.get() instanceof PollStatus.CannotVerify
        !status.get().reason().isBlank()
        status.get().details() != null
    }
}
