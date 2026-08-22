package com.github.oinsio.gnomish.app.sandboxlifecycle

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Duration
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * {@link Slf4jSweepVerdictListener}, task 4.3 of add-serve-sandbox-lifecycle (NFR-O4): the
 * structured sink `run`/`take` use, asserted on the emitted event rather than on a rendered
 * string, so the field set — not one formatter's spacing — is what the gate holds.
 */
class Slf4jSweepVerdictListenerSpec extends Specification {

    /** The sink logs under its own category, not under the class name (design D6). */
    static final String CATEGORY = 'gnomish.sandbox.lifecycle'

    private static SweepVerdict verdict(Duration age = Duration.ofHours(30)) {
        new SweepVerdict(
                SweepVerdictCategory.STOPPED_ORPHAN,
                'gnomish-task-7-box',
                'main-box',
                'tracked',
                'task-7',
                'unowned running main-box',
                age)
    }

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(CATEGORY)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    // NFR-O4: one line per verdict, carrying EVERY field of the event — a sink that dropped the
    //     task key or the reason would leave an operator unable to act on the line.
    def "one verdict logs one line under the lifecycle category, carrying every field"() {
        when:
        def events = capture {
            new Slf4jSweepVerdictListener().onVerdict(verdict())
        }

        then:
        events.size() == 1
        events[0].loggerName == CATEGORY
        events[0].argumentArray.toList() == [
            SweepVerdictCategory.STOPPED_ORPHAN,
            'gnomish-task-7-box',
            'main-box',
            'tracked',
            'task-7',
            'unowned running main-box',
            Duration.ofHours(30)
        ]
        events[0].formattedMessage == 'sweep STOPPED_ORPHAN object=gnomish-task-7-box role=main-box ' +
                'mode=tracked task=task-7 reason="unowned running main-box" age=PT30H'
    }

    // NFR-O4: `age` is nullable on the wire (see SweepVerdict); the sink must still emit its line
    //     rather than blow up on a verdict reached without a measured age.
    def "a verdict without a measured age still logs its line"() {
        when:
        def events = capture {
            new Slf4jSweepVerdictListener().onVerdict(verdict(null))
        }

        then:
        events.size() == 1
        events[0].formattedMessage.endsWith('age=null')
    }

    // NFR-O4: every entry point sinks through this one listener, so a two-object pass must read
    //     as two lines — never one summary line the per-object vocabulary is lost in.
    def "each verdict of a pass gets its own line"() {
        when:
        def events = capture {
            def listener = new Slf4jSweepVerdictListener()
            listener.onVerdict(verdict())
            listener.onVerdict(verdict(Duration.ofDays(2)))
        }

        then:
        events.size() == 2
        events*.formattedMessage*.endsWith('age=PT30H') == [true, false]
    }
}
