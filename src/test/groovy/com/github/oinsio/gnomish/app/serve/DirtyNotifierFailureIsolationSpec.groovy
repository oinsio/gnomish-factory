package com.github.oinsio.gnomish.app.serve

import spock.lang.Specification

/**
 * {@link DirtyNotifier#markDirtySafely}: the belt-and-suspenders boundary between a pluggable
 * {@link DirtyNotifier} implementation and the feed/slot call sites that fire it. {@link
 * DirtyNotifier#markDirty()}'s real implementation is expected to be trivial and non-throwing,
 * but a future or misbehaving implementation must never be able to break the caller's own state
 * transition (task 3.6, NFR-R1 of add-serve-observability).
 *
 * <p>Implements NFR-R1 of add-serve-observability.
 */
class DirtyNotifierFailureIsolationSpec extends Specification {

    def "a notifier that throws does not propagate out of markDirtySafely"() {
        given:
        DirtyNotifier notifier = { -> throw new RuntimeException('writer thread died mid-notify') }

        when:
        DirtyNotifier.markDirtySafely(notifier, 'test-call-site')

        then:
        noExceptionThrown()
    }

    def "a notifier that succeeds is still invoked exactly once"() {
        given:
        def calls = 0
        DirtyNotifier notifier = { -> calls++ }

        when:
        DirtyNotifier.markDirtySafely(notifier, 'test-call-site')

        then:
        calls == 1
    }
}
