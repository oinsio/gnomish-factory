package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * FR6, FR4, FR13 of harden-logging-observability: {@link MidRoundPollLog} is the single sink every
 * mid-round poll failure reaches — the tip-resolution evidence {@link VerifiedTip#failureReason}
 * builds out of git's stderr, and the harvest failures that carry in-container output — so it is
 * the layer that owns routing that untrusted text through {@code LogText}. The obligation was
 * carried only by the code and its comment; these are its pins.
 */
class MidRoundPollLogSpec extends Specification {

    static final String ESC = Character.toString(27)

    def suppressorClock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    def pollLog = new MidRoundPollLog(
    LoggerFactory.getLogger(MidRoundPollLogSpec),
    new RepeatSuppressor(suppressorClock, Duration.ofMinutes(5)),
    'PROJ-1',
    'gnomish/PROJ-1')

    def "FR6: a poisoned failure reason cannot forge a record on any suppression edge"() {
        given:
        def logs = LogCaptureSupport.attach(MidRoundPollLogSpec, Level.DEBUG)
        def poisoned = "fatal: bad revision\n2026-01-01 ERROR forged record${ESC}[31m"

        when: 'the same poisoned reason reports on the first occurrence and on a repeat'
        2.times { pollLog.failed(subject, poisoned, null) }

        then: 'every emitted record is one line, with no control sequences left in it'
        logs.list.size() == 2
        logs.list.every {
            !it.formattedMessage.contains('\n') && !it.formattedMessage.contains(ESC)
        }

        and: 'the evidence itself still reaches the operator'
        logs.list.every { it.formattedMessage.contains('forged record') }

        cleanup:
        logs.detach()

        where:
        subject << MidRoundPollLog.Subject.values()
    }

    def "FR6: the reason a recovery line quotes back is the sanitized one"() {
        given:
        def logs = LogCaptureSupport.attach(MidRoundPollLogSpec, Level.INFO)

        when:
        pollLog.failed(MidRoundPollLog.Subject.TIP, "fatal: bad revision\nforged${ESC}[31m", null)
        pollLog.recovered(MidRoundPollLog.Subject.TIP)

        then:
        def recovery = logs.list.find { it.level == Level.INFO }
        recovery != null
        !recovery.formattedMessage.contains('\n')
        !recovery.formattedMessage.contains(ESC)
        recovery.formattedMessage.contains('forged')

        cleanup:
        logs.detach()
    }
}
