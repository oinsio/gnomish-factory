package com.github.oinsio.gnomish.app.serve

import spock.lang.Specification

/**
 * {@link ForwardingDirtyNotifier}: the construction-order-cycle seam task 5.1 uses to hand
 * {@link com.github.oinsio.gnomish.app.serve.SlotLedger}/{@link FeedAutomaton}/{@link
 * LifecycleStateTracker} a real notifier before the {@code SnapshotWriter} they will eventually
 * wake even exists.
 *
 * <p>Implements FR1 of add-serve-observability.
 */
class ForwardingDirtyNotifierSpec extends Specification {

    def "markDirty() before bind() is a harmless no-op (defaults to DirtyNotifier.NOOP)"() {
        given:
        def notifier = new ForwardingDirtyNotifier()

        when:
        notifier.markDirty()

        then:
        noExceptionThrown()
    }

    def "markDirty() after bind() forwards to the bound delegate"() {
        given:
        def notifier = new ForwardingDirtyNotifier()
        def calls = 0
        notifier.bind({ -> calls++ } as DirtyNotifier)

        when:
        notifier.markDirty()
        notifier.markDirty()

        then:
        calls == 2
    }

    def "a later bind() call rebinds subsequent markDirty() calls to the new delegate"() {
        given:
        def notifier = new ForwardingDirtyNotifier()
        def firstCalls = 0
        def secondCalls = 0
        notifier.bind({ -> firstCalls++ } as DirtyNotifier)
        notifier.markDirty()

        when:
        notifier.bind({ -> secondCalls++ } as DirtyNotifier)
        notifier.markDirty()

        then:
        firstCalls == 1
        secondCalls == 1
    }
}
