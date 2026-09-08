package com.github.oinsio.gnomish.app.serve

import java.time.Instant
import spock.lang.Specification

/**
 * {@link ForwardingRemoteOutageLedgerSink}: the construction-order-cycle seam task 7.4 of
 * add-base-ref-resolution uses to hand every {@link RemoteOutageGate} a real ledger sink before the
 * {@code RemoteOutageLedgerWriter} it will eventually feed even exists — mirrors {@link
 * ForwardingDirtyNotifier}'s role for the same cycle.
 *
 * <p>Implements NFR-O1, NFR-O3 of add-base-ref-resolution.
 */
class ForwardingRemoteOutageLedgerSinkSpec extends Specification {

    private static RemoteOutageClosedOutage outage() {
        new RemoteOutageClosedOutage('origin', Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 1, 0, 'boom')
    }

    def "accept() before bind() is a harmless no-op"() {
        given:
        def sink = new ForwardingRemoteOutageLedgerSink()

        when:
        sink.accept(outage())

        then:
        noExceptionThrown()
    }

    def "accept() after bind() forwards the outage to the bound delegate"() {
        given:
        def sink = new ForwardingRemoteOutageLedgerSink()
        def received = []
        sink.bind({ o -> received << o })

        when:
        sink.accept(outage())

        then:
        received == [outage()]
    }

    def "a later bind() call rebinds subsequent accept() calls to the new delegate"() {
        given:
        def sink = new ForwardingRemoteOutageLedgerSink()
        def first = []
        def second = []
        sink.bind({ o -> first << o })
        sink.accept(outage())

        when:
        sink.bind({ o -> second << o })
        sink.accept(outage())

        then:
        first.size() == 1
        second.size() == 1
    }
}
