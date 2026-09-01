package com.github.oinsio.gnomish.app.sandboxlifecycle

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import spock.lang.Specification

/**
 * {@link Slf4jSweepVerdictListener}, task 4.3 of add-serve-sandbox-lifecycle (NFR-O4): the
 * structured sink `run`/`take` use, asserted on the emitted event rather than on a rendered
 * string, so the field set — not one formatter's spacing — is what the gate holds.
 *
 * <p>And the level grading of `sandbox-lifecycle` "Quiet tick, loud degradation" (FR12 of
 * harden-logging-observability): a healthy tick says nothing on the operator console, while a
 * degraded one is loud in the same tick.
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

    /** Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.5 touched this spec. */
    private static List<ILoggingEvent> capture(Level level = Level.DEBUG, Closure<Void> emit) {
        def logs = LogCaptureSupport.attach(CATEGORY, level)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    private static SweepVerdict of(SweepVerdictCategory category) {
        new SweepVerdict(category, 'gnomish-task-7-box', 'main-box', 'tracked', 'task-7', 'reason', null)
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

    // FR12: the level is the verdict's own category, decided once — a data-driven table so a new
    //     category cannot be added without a row here saying what an operator should do about it.
    def "FR12: #category logs at #expected"() {
        when:
        def events = capture {
            new Slf4jSweepVerdictListener().onVerdict(of(category))
        }

        then:
        events.size() == 1
        events[0].level == expected

        where:
        category || expected
        SweepVerdictCategory.CHECKED_ALIVE || Level.DEBUG
        SweepVerdictCategory.KEPT_UNDER_THRESHOLD || Level.DEBUG
        SweepVerdictCategory.STOPPED_ORPHAN || Level.INFO
        SweepVerdictCategory.DISPOSED_AGED || Level.INFO
        SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE || Level.INFO
        SweepVerdictCategory.SKIPPED_NO_VERDICT || Level.WARN
    }

    // sandbox-lifecycle "Quiet tick, loud degradation": the whole point of the grading is that one
    //     bad object in an otherwise healthy tick is the only thing the operator plane shows.
    def "FR12: a quiet tick stays off the console while its one degradation does not"() {
        when: 'a tick over three living, young objects and one the sweep could not read'
        def events = capture(Level.INFO) {
            def listener = new Slf4jSweepVerdictListener()
            listener.onVerdict(of(SweepVerdictCategory.CHECKED_ALIVE))
            listener.onVerdict(of(SweepVerdictCategory.KEPT_UNDER_THRESHOLD))
            listener.onVerdict(of(SweepVerdictCategory.CHECKED_ALIVE))
            listener.onVerdict(of(SweepVerdictCategory.SKIPPED_NO_VERDICT))
        }

        then: 'the steady-state three are below INFO entirely; the degradation is a WARN'
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.contains('SKIPPED_NO_VERDICT')
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
