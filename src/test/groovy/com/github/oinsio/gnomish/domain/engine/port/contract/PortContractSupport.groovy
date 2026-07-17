package com.github.oinsio.gnomish.domain.engine.port.contract

import org.opentest4j.TestAbortedException

/**
 * The arrangement-seam opt-out mechanism shared by every port-contract suite.
 *
 * <p>Each contract row asks its subclass to {@code arrange} an adapter that produces
 * a specific sealed variant. A subclass that CANNOT produce that variant returns an
 * empty {@link Optional}; the row then calls {@link #assumeProducible}, which records
 * the gap as a port-shape finding and aborts the row (via {@link TestAbortedException},
 * reported by the JUnit Platform as "skipped") rather than failing the suite. This is
 * the design.md requirement: "Interactive adapters cannot produce some port-contract
 * variants … treat as findings about port shapes and record them; do not fake
 * pathological inputs just to pass a suite row." Scripted-fake subclasses (this task,
 * add-manual-run §2.1) produce every variant, so no row is ever skipped for them; the
 * interactive subclasses (§5.5) use this opt-out.
 *
 * <p>FR14 of add-manual-run: the SAME suites run against fakes, real and interactive
 * adapters (metric M2).
 */
trait PortContractSupport {

    /** Variants a subclass declared unproducible, recorded for the port-shape report. */
    final List<String> unproducibleVariants = []

    /**
     * Passes when the variant is producible; otherwise records a port-shape finding
     * and aborts the current feature (reported as skipped), so an adapter that
     * legitimately cannot arrange a row neither fails the suite nor silently ignores
     * the gap.
     *
     * @param adapter the arranged adapter, or empty when the variant is unproducible
     * @param port the port under test, for the recorded finding
     * @param variant the variant the row needs, for the recorded finding
     */
    void assumeProducible(Optional<?> adapter, String port, String variant) {
        if (adapter.isEmpty()) {
            def finding = "${port}: cannot produce ${variant}".toString()
            unproducibleVariants << finding
            throw new TestAbortedException("port-shape finding — ${finding}".toString())
        }
    }
}
