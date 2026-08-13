package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import spock.lang.Specification

/**
 * SlotLedger's {@link DirtyNotifier} trigger point (FR1, design D4 of add-serve-observability):
 * {@link SlotLedger#assign} and {@link SlotLedger#release} — the occupancy-changing calls — wake
 * the snapshot writer immediately rather than waiting for its timer beat. {@link
 * SlotLedger#acquire} and {@link SlotLedger#abandon} touch only the free-permit count, not
 * occupancy, so they must not fire a spurious write.
 *
 * Implements FR1 of add-serve-observability.
 */
class SlotLedgerDirtyNotifierSpec extends Specification {

    private static final TaskRef A = new TaskRef('github:o/r#1')

    def "assign wakes the dirty notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def ledger = new SlotLedger(1, new com.github.oinsio.gnomish.adapter.engine.SystemClock(), notifier)

        when:
        ledger.acquire()
        ledger.assign(A)

        then:
        1 * notifier.markDirty()
    }

    def "release wakes the dirty notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def ledger = new SlotLedger(1, new com.github.oinsio.gnomish.adapter.engine.SystemClock(), notifier)
        ledger.acquire()
        ledger.assign(A)

        when:
        ledger.release(A)

        then:
        1 * notifier.markDirty()
    }

    def "acquire alone does not wake the dirty notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def ledger = new SlotLedger(1, new com.github.oinsio.gnomish.adapter.engine.SystemClock(), notifier)

        when:
        ledger.acquire()

        then:
        0 * notifier.markDirty()
    }

    def "abandon does not wake the dirty notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def ledger = new SlotLedger(1, new com.github.oinsio.gnomish.adapter.engine.SystemClock(), notifier)
        ledger.acquire()

        when:
        ledger.abandon()

        then:
        0 * notifier.markDirty()
    }

    def "a rejected assign of an already-occupied task does not fire again"() {
        given: 'the first assign already fired its own notification'
        def notifier = Mock(DirtyNotifier)
        def ledger = new SlotLedger(2, new com.github.oinsio.gnomish.adapter.engine.SystemClock(), notifier)
        ledger.acquire()
        ledger.assign(A)

        when: 'a second slot tries to claim the same already-occupied task'
        ledger.acquire()
        ledger.assign(A)

        then:
        thrown(IllegalStateException)
        0 * notifier.markDirty()
    }

    // NFR-R1 (task 3.6): a throwing DirtyNotifier must not break the slot ledger's own
    // occupancy bookkeeping — assign/release must still complete normally.
    def "a throwing dirty notifier does not propagate out of assign or release"() {
        given:
        DirtyNotifier notifier = {
            -> throw new RuntimeException('notifier boom')
        }
        def ledger = new SlotLedger(1, new com.github.oinsio.gnomish.adapter.engine.SystemClock(), notifier)

        when:
        ledger.acquire()
        ledger.assign(A)

        then:
        noExceptionThrown()
        ledger.occupiedRefs() == [A] as Set

        when:
        ledger.release(A)

        then:
        noExceptionThrown()
        ledger.occupiedRefs().isEmpty()
    }

    def "the existing two-arg constructor defaults to a no-op notifier"() {
        given:
        def ledger = new SlotLedger(1)

        when:
        ledger.acquire()
        ledger.assign(A)
        ledger.release(A)

        then:
        noExceptionThrown()
    }
}
