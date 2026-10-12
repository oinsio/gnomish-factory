package com.github.oinsio.gnomish.architecture

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.logging.LogExpectationLedger
import com.github.oinsio.gnomish.testfixtures.logging.OperatorLogEvents
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * FR14, FR15 of harden-logging-observability: what an operator line's pin is made of. The code is
 * the contract, so everything keyed on it — a spec's match, an operator's grep, the runtime gate's
 * ledger — reads a reworded line exactly as it read the old one. The level is the contract too: the
 * project's one capture idiom pins at INFO, so a WARN demoted to DEBUG never reaches the spec that
 * pinned it.
 *
 * <p>Two constraints shared with {@link LogExpectationGateSpec}: no coded line is emitted (a real
 * code emitted here would count as watched for the whole run), so the reworded lines are events as
 * data on a ledger of this spec's own; and the line this spec does emit carries no code.
 */
class OperatorLinePinSpec extends Specification {

    private static final Logger FACTORY_LOG = LoggerFactory.getLogger(OperatorLinePinSpec)

    private static final OperatorEvent EVENT = OperatorEvent.values().first()

    /** An operator's grep: the code head, wherever it sits in the line. */
    private static final String GREP = /\[GF\d{3}]/

    private static ILoggingEvent unemitted(String message) {
        def event = new LoggingEvent()
        event.level = Level.WARN
        event.loggerName = OperatorLinePinSpec.name
        event.message = message
        event
    }

    // Scenario: observability/factory-logging / Wording drifts, contract holds
    def "FR14: a reworded operator line is matched by everything keyed on its code, exactly as before"() {
        given: 'the same call site before and after its prose was reworded'
        def before = unemitted(EVENT.head() + 'push of the task branch failed')
        def after = unemitted(EVENT.head() + 'could not push the task branch to origin')

        expect: 'a spec keyed on the catalog constant matches both'
        [before, after].every { it.formattedMessage.startsWith(EVENT.head()) }

        and: 'an operator grep on the code head finds the same code in both'
        [before, after].collect {
            (it.formattedMessage =~ GREP)[0]
        } == [
            EVENT.head().trim(),
            EVENT.head().trim()
        ]

        and: 'the runtime gate records the same watched code for both: its contract did not move'
        observationsOf(before) == observationsOf(after)
        observationsOf(after) == "watched\t${EVENT.code()}\n"
    }

    // Scenario: observability/factory-logging / The level is part of the pin
    def "FR15: a pinned WARN line demoted to DEBUG no longer satisfies the spec that pinned it"() {
        given: 'a spec pinning the line through the project\'s capture idiom, which listens at INFO'
        def capture = LogCaptureSupport.attach(OperatorLinePinSpec)

        when: 'the line is emitted at WARN, then the same line at DEBUG'
        FACTORY_LOG.warn('degrade line under pin')
        def atWarn = List.copyOf(capture.list)
        capture.list.clear()
        FACTORY_LOG.debug('degrade line under pin')
        def atDebug = List.copyOf(capture.list)

        then: 'the pin holds for the WARN line'
        atWarn*.formattedMessage == ['degrade line under pin']
        atWarn*.level == [Level.WARN]

        and: 'the demoted line never reaches it, so the pin goes red with the text unchanged'
        atDebug.isEmpty()

        cleanup:
        capture.detach()
    }

    private static String observationsOf(ILoggingEvent event) {
        def ledger = new LogExpectationLedger()
        ledger.record('SomeSpec.pins the line', new OperatorLogEvents([event], []), false)
        ledger.observations()
    }
}
