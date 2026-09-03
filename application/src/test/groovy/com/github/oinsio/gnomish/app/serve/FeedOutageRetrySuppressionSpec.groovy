package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.time.MovableClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * FR4 / UX3 of harden-logging-observability: a sustained tracker outage is exactly the shape the
 * repeat-suppression rule exists for. {@link FeedOutageRetry} retries indefinitely at the feed's
 * poll interval, so an unsuppressed WARN per attempt is a per-poll flood in a healthy-console
 * daemon — the operator sees one fault repeated for as long as the tracker is down instead of one
 * WARN, periodic counted roll-ups, and a recovery line.
 */
class FeedOutageRetrySuppressionSpec extends Specification {

    /** Virtual time: the roll-up interval is minutes long and no spec may sleep. */
    MovableClock clock = new MovableClock(Instant.parse('2026-09-03T10:00:00Z'))

    RepeatSuppressor suppressor = new RepeatSuppressor(clock, RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL)

    /** Advances virtual time by one poll interval per backoff, as the real feed's pause does. */
    Sleeper sleeper = { Duration d -> clock.advance(d) } as Sleeper

    private static final Duration POLL = Duration.ofSeconds(30)

    // FR4/UX3: the tenth consecutive failure of one outage is not the tenth WARN.
    def "a sustained outage is one WARN plus periodic roll-ups, not one WARN per retry"() {
        given:
        def retry = new FeedOutageRetry(sleeper, { POLL }, suppressor)
        def attempts = new AtomicInteger()
        def logs = LogCaptureSupport.attach(FeedOutageRetry, Level.DEBUG)

        when: 'the tracker is down for fifteen polls — over seven minutes — and then answers'
        def result = retry.run('feed poll', {
            ->
            if (attempts.incrementAndGet() <= 15) {
                throw new RuntimeException('tracker down')
            }
            'ready'
        })

        then:
        result == 'ready'
        attempts.get() == 16

        and: 'the operator plane carries edges only: the first occurrence and the roll-ups'
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() < 15
        warnings[0].formattedMessage.startsWith(OperatorEvent.FEED_TRACKER_OUTAGE_SUSPECTED.head())
        warnings[0].formattedMessage.contains('feed poll')

        and: 'the quiet period returns a counted reminder rather than silence'
        def rollUps = warnings.findAll {
            it.formattedMessage.startsWith(OperatorEvent.FEED_TRACKER_OUTAGE_ROLLUP.head())
        }
        rollUps.size() == 1
        rollUps[0].formattedMessage.contains('feed poll')

        and: 'the polls in between are DEBUG, so nothing about the outage is lost'
        logs.list.count { it.level == Level.DEBUG } > 0

        and: 'and the outage ends with one recovery line, so the last word is not the failure'
        def recovery = logs.list.find { it.level == Level.INFO }
        recovery != null
        recovery.formattedMessage.contains('feed poll')

        cleanup:
        logs.detach()
    }

    // FR4: the streak identity carries the fault's own words, so the operator reads what broke...
    def "the announced outage names the fault, not just that something failed"() {
        given:
        def retry = new FeedOutageRetry(sleeper, { POLL }, suppressor)
        def attempts = new AtomicInteger()
        def logs = LogCaptureSupport.attach(FeedOutageRetry)

        when:
        retry.run('feed poll', {
            ->
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException('tracker returned 503')
            }
            'ready'
        })

        then:
        def announced = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FEED_TRACKER_OUTAGE_SUSPECTED.head())
        }
        announced.formattedMessage.contains('java.lang.IllegalStateException')
        announced.formattedMessage.contains('tracker returned 503')

        cleanup:
        logs.detach()
    }

    // FR4: ...and a fault with no words is named by its type, never as a literal "null".
    def "a fault with no message is named by its type alone"() {
        given:
        def retry = new FeedOutageRetry(sleeper, { POLL }, suppressor)
        def attempts = new AtomicInteger()
        def logs = LogCaptureSupport.attach(FeedOutageRetry)

        when:
        retry.run('feed poll', {
            ->
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException()
            }
            'ready'
        })

        then:
        def announced = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FEED_TRACKER_OUTAGE_SUSPECTED.head())
        }
        announced.formattedMessage.contains('java.lang.RuntimeException')
        !announced.formattedMessage.contains('null')

        cleanup:
        logs.detach()
    }
}
