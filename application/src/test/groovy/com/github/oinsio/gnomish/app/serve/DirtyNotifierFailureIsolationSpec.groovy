package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
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
        DirtyNotifier notifier = {
            -> throw new RuntimeException('writer thread died mid-notify')
        }

        and:
        def logs = LogCaptureSupport.attach(DirtyNotifier)

        when:
        DirtyNotifier.markDirtySafely(notifier, 'test-call-site')

        then:
        noExceptionThrown()

        and: 'FR15 of harden-logging-observability: a delayed snapshot write is explained by a coded WARN naming the call site'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.DIRTY_NOTIFIER_FAILED.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains('test-call-site')

        cleanup:
        logs.detach()
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
