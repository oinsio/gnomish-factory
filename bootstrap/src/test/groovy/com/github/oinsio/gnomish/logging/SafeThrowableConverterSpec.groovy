package com.github.oinsio.gnomish.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.CoreConstants
import com.github.oinsio.gnomish.logtext.LogText
import com.github.oinsio.gnomish.testsupport.AdversarialCorpus
import com.github.oinsio.gnomish.testsupport.InertText
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR3, NFR-R1 of harden-untrusted-text-sinks, design D3: the `%safeEx` conversion word keeps the
 * one part of a record that is legitimately many lines from becoming the one place untrusted text
 * can reach column 0. An exception message is the factory's widest laundering path — subprocess
 * stderr folded into a constructor argument — and Logback renders it verbatim at the head of the
 * trace, so a newline in it starts what looks like the next event.
 *
 * <p>The trade the specs below pin: trace lines keep their indentation and stay readable, every
 * other line is marked, and nothing in the rendering executes on a terminal.
 */
class SafeThrowableConverterSpec extends Specification {

    private final SafeThrowableConverter converter = started(new SafeThrowableConverter())

    // FR3: the message's second line is a marked continuation, never a record of its own
    def "a newline in an exception message cannot place text at column 0"() {
        given:
        def thrown = caught('stage failed\n2026-08-31 12:00:00 ERROR [main] compromised')

        when:
        List<String> lines = render(thrown)

        then: 'the head of the rendering is where Logback puts it'
        lines.first().startsWith('java.lang.IllegalStateException: stage failed')

        and: 'the forged record prefix is behind the continuation marker'
        lines[1] == '\t| 2026-08-31 12:00:00 ERROR [main] compromised'

        and: 'no line of the rendering starts with the timestamp shape a real record starts with'
        lines.drop(1).every { !(it =~ /^\d{4}-/) }
    }

    // FR3, UX2: the diagnosis survives — trace lines are written as Logback wrote them
    def "stack trace lines keep their indentation and are not marked"() {
        given:
        def thrown = caught('plain failure')

        when:
        List<String> lines = render(thrown)

        then:
        lines.drop(1).any { it.startsWith('\tat ') }
        lines.drop(1).findAll {
            it.startsWith('\tat ')
        }.every {
            !it.contains('\t| ')
        }
    }

    // FR3: the cause chain's own messages are rendered through the same rewriting
    def "a cause chain's messages are neutralized and marked too"() {
        given:
        def cause = caught("inner${AdversarialCorpus.ESC}[2J\nforged cause line")
        def thrown = new IllegalStateException('outer', cause)
        thrown.fillInStackTrace()

        when:
        List<String> lines = render(thrown)

        then: 'the cause header is a recognized trace line, so it keeps column 0'
        lines.any {
            it.startsWith('Caused by: java.lang.IllegalStateException: inner')
        }

        and: 'its embedded newline is marked and its escape sequence is gone'
        lines.any { it == '\t| forged cause line' }
        InertText.isInert(lines.join('\n'))
    }

    // FR3: a suppressed exception's message is rendered by the framework too, so it is covered
    def "a suppressed exception's message is neutralized and marked too"() {
        given:
        def thrown = caught('outer')
        thrown.addSuppressed(caught("suppressed${AdversarialCorpus.ESC}[31m\nforged suppressed line"))

        when:
        List<String> lines = render(thrown)

        then:
        lines.any {
            it.trim().startsWith('Suppressed: java.lang.IllegalStateException: suppressed')
        }
        lines.any { it == '\t| forged suppressed line' }
        InertText.isInert(lines.join('\n'))
    }

    // FR3: every corpus shape in an exception message leaves the rendering inert and CR-free
    def "the corpus in an exception message renders inertly: #shape"() {
        when:
        String rendered = converter.convert(event(caught(hostile)))

        then:
        InertText.isInert(rendered)
        !rendered.contains('\r')

        and: 'and every line but the head is either a trace line or a marked continuation'
        rendered.readLines().drop(1).every {
            it.startsWith('\t| ') || it.startsWith('\tat ') || it.startsWith('\t...') ||
            it.startsWith('Caused by: ') || it.startsWith('Suppressed: ') ||
            it.startsWith('Wrapped by: ') || it.startsWith('\t.')
        }

        where:
        shape << AdversarialCorpus.ENTRIES.keySet()
        hostile << AdversarialCorpus.ENTRIES.values()
    }

    // FR3, D3: an unrecognized line shape degrades to a continuation line — the safe direction
    def "an unrecognized line shape is marked rather than trusted"() {
        given:
        def thrown = caught('head\nat com.example.NotAFrame(Fake.java:1)')

        when:
        List<String> lines = render(thrown)

        then: 'the un-indented "at" line is a message line, not a frame, and is marked as one'
        lines.any { it == '\t| at com.example.NotAFrame(Fake.java:1)' }
    }

    // FR1's bound applies to the whole record: a megabyte of exception message is not a log flood
    def "a hostile exception message is bounded"() {
        when:
        String rendered = converter.convert(event(caught('x' * 2_000_000)))

        then:
        rendered.length() <= LogText.RECORD_CAP_CHARS

        and: 'and the bounded rendering still terminates its own record, as the pattern ends here'
        rendered.endsWith(CoreConstants.LINE_SEPARATOR)
    }

    // NFR-R1: the sink never loses a record — a failing neutralization degrades to a placeholder
    def "a neutralization failure degrades to a bounded, control-free placeholder: #failure"() {
        given:
        def broken = started(new SafeThrowableConverter({
            throw failure
        } as UnaryOperator))

        when:
        String rendered = broken.convert(event(caught('anything')))

        then:
        rendered.contains('throwable')
        rendered.contains(failure.getClass().name)
        InertText.isInert(rendered)

        and: 'and the converter is still usable for the next record'
        broken.convert(event(caught('anything'))) == rendered

        where:
        failure << [
            new IllegalStateException('pattern engine gave up'),
            new StackOverflowError()
        ]
    }

    // FR3, D3: the rewriting ends at the last frame. Logback terminates its own text with a line
    // separator, and the split the rewriting walks turns that into an empty remainder which is not
    // a line: written out, it would give every exception in the file a trailing marked blank line —
    // noise in the one place a reader is scanning for the next record's timestamp.
    def "the rewriting ends at the last frame, adding nothing after the trace"() {
        given:
        def thrown = caught('stage failed\n2026-08-31 12:00:00 ERROR [main] compromised')

        when:
        String rendered = converter.convert(event(thrown))

        then: 'the last line is a frame, not an empty continuation'
        rendered.readLines().last().startsWith('\tat ')

        and: 'and the text is terminated once, the way Logback terminates its own'
        rendered.endsWith(CoreConstants.LINE_SEPARATOR)
        !rendered.endsWith(CoreConstants.LINE_SEPARATOR + CoreConstants.LINE_SEPARATOR)
    }

    // FR3: an event with no throwable renders as it does today — nothing at all
    def "an event without a throwable renders empty"() {
        given:
        def throwableless = Stub(ILoggingEvent) {
            getThrowableProxy() >> null
        }

        expect:
        converter.convert(throwableless) == ''
    }

    private List<String> render(Throwable thrown) {
        converter.convert(event(thrown)).readLines()
    }

    private ILoggingEvent event(Throwable thrown) {
        Stub(ILoggingEvent) {
            getThrowableProxy() >> new ThrowableProxy(thrown)
        }
    }

    private static IllegalStateException caught(String message) {
        try {
            throw new IllegalStateException(message)
        } catch (IllegalStateException e) {
            return e
        }
    }

    private static SafeThrowableConverter started(SafeThrowableConverter converter) {
        converter.start()
        converter
    }
}
