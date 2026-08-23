package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * NFR-O1, UX2 of add-factory-serve: the daemon log carries ONE line per bottleneck, not one per
 * idle poll. Two rules make that true and are pinned here — a state is logged only when it differs
 * from the last one reported, and the {@code FULL} vantage point fires only when the slot just
 * filled was the last free one.
 *
 * <p>The second rule had no spec: {@code onSlotFilled} is called after every claim, so a logger
 * that ignored its {@code freeSlots == 0} guard would print "all slots occupied" on every claim of
 * a half-idle daemon — exactly the per-cycle noise this class exists to prevent.
 */
class FeedStateLoggerSpec extends Specification {

    private static final int WIP_LIMIT = 4

    private final Logger automatonLogger = (Logger) LoggerFactory.getLogger(FeedAutomaton)
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>()

    def setup() {
        appender.start()
        automatonLogger.addAppender(appender)
    }

    def cleanup() {
        automatonLogger.detachAppender(appender)
        appender.stop()
    }

    private List<ILoggingEvent> infoEvents() {
        appender.list.findAll { it.level == Level.INFO }
    }

    def "the slot that spends the last free one reports FULL"() {
        when:
        new FeedStateLogger().onSlotFilled(0, WIP_LIMIT)

        then:
        infoEvents().size() == 1
        infoEvents()[0].formattedMessage.contains('all slots occupied')
    }

    def "a claim that leaves #freeSlots slot(s) free reports nothing"() {
        when:
        new FeedStateLogger().onSlotFilled(freeSlots, WIP_LIMIT)

        then: 'the daemon is not full, so there is no bottleneck to announce'
        appender.list.isEmpty()

        where:
        freeSlots << [1, 2, WIP_LIMIT]
    }

    def "a state repeated across cycles is logged once, not once per cycle"() {
        given:
        def logger = new FeedStateLogger()

        when: 'the same blocked state is observed three cycles running'
        3.times { logger.onTransition(FeedState.IDLE_BLOCKED, 2, WIP_LIMIT) }

        then: 'one line, naming the bottleneck count and the WIP limit'
        infoEvents().size() == 1
        infoEvents()[0].formattedMessage.contains('2 front(s) await human decisions')
        infoEvents()[0].formattedMessage.contains("WIP limit ${WIP_LIMIT}")
    }

    def "a state that changes back is reported again"() {
        given:
        def logger = new FeedStateLogger()

        when:
        logger.onTransition(FeedState.IDLE_BLOCKED, 1, WIP_LIMIT)
        logger.onTransition(FeedState.FILLING, 0, WIP_LIMIT)
        logger.onTransition(FeedState.IDLE_BLOCKED, 1, WIP_LIMIT)

        then:
        infoEvents().size() == 2
        infoEvents().every {
            it.formattedMessage.contains('await human decisions')
        }
    }
}
