package com.github.oinsio.gnomish.domain.engine.port.contract

import org.opentest4j.TestAbortedException
import spock.lang.Specification

/**
 * Proves the arrangement-seam opt-out mechanism the abstract suites share: a
 * subclass that declares a variant unproducible (empty {@link Optional}) has the row
 * recorded as a port-shape finding and aborted (skipped), never failed; a producible
 * variant passes straight through. Task add-manual-run §5.5 reuses this exact
 * mechanism for the interactive adapters.
 *
 * <p>FR14 of add-manual-run: the same suites run against fakes, real and interactive
 * adapters (metric M2).
 */
class PortContractSupportSpec extends Specification implements PortContractSupport {

    // FR14: an unproducible variant records a port-shape finding and aborts the row
    def "an unproducible variant is recorded and aborts the row"() {
        when: 'a subclass declares a variant unproducible'
        assumeProducible(Optional.empty(), 'SomePort', 'SomeVariant')

        then: 'the row is aborted (JUnit reports it as skipped, not failed)'
        def aborted = thrown(TestAbortedException)
        aborted.message.contains('SomePort')
        aborted.message.contains('SomeVariant')

        and: 'the gap is recorded as a port-shape finding'
        unproducibleVariants == [
            'SomePort: cannot produce SomeVariant'
        ]
    }

    // FR14: a producible variant passes through without aborting or recording anything
    def "a producible variant passes through"() {
        when: 'a subclass supplies an arranged adapter'
        assumeProducible(Optional.of(new Object()), 'SomePort', 'SomeVariant')

        then: 'no abort is raised and nothing is recorded'
        noExceptionThrown()
        unproducibleVariants.isEmpty()
    }
}
