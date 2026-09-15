package com.github.oinsio.gnomish.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.logtext.LogText
import com.github.oinsio.gnomish.testsupport.AdversarialCorpus
import com.github.oinsio.gnomish.testsupport.InertText
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR1, FR2, NFR-R1 of harden-untrusted-text-sinks, design D1/D2: the `%safeMsg` conversion word is
 * the layer that does not depend on caller discipline. A call site that assembled its message from
 * untrusted text three calls earlier leaves no accessor for the source gate to see, so what the
 * encoder is handed is the last place the record can still be made safe.
 *
 * <p>The idempotence feature is the other half of the contract: a layer that changed what correct
 * call sites already produce would be a layer nobody could add to a live system.
 */
class SafeMessageConverterSpec extends Specification {

    private final SafeMessageConverter converter = new SafeMessageConverter()

    // FR1: whatever the call site assembled, the rendered message is inert, single-line and bounded
    def "a hostile formatted message renders inertly: #shape"() {
        when:
        String rendered = converter.convert(event(hostile))

        then:
        InertText.isInert(rendered)
        InertText.isSingleLine(rendered)
        rendered.length() <= LogText.RECORD_CAP_CHARS

        where:
        shape << AdversarialCorpus.ENTRIES.keySet()
        hostile << AdversarialCorpus.ENTRIES.values()
    }

    // FR1: a forged record prefix arrives as visible text on the one line, never as a second record
    def "a forged record prefix cannot open a second record"() {
        when:
        String rendered = converter.convert(event('stage failed\n2026-08-31 12:00:00 ERROR [main] compromised'))

        then:
        rendered == 'stage failed\\n2026-08-31 12:00:00 ERROR [main] compromised'
    }

    // FR2, G3: the sink is invisible to a call site that used the choke point — no second escaping
    // of the visible newline marker, no second cap below the first
    def "choke-point output renders byte-identically: #shape"() {
        given:
        String prepared = LogText.forLog(hostile)

        expect:
        converter.convert(event(prepared)) == prepared

        where:
        shape << AdversarialCorpus.ENTRIES.keySet()
        hostile << AdversarialCorpus.ENTRIES.values()
    }

    // FR1: plain text is untouched, which is what UX1 promises the operator
    def "a message with nothing to neutralize is passed through unchanged"() {
        expect:
        converter.convert(event('claimed task GNOME-17')) == 'claimed task GNOME-17'
    }

    // NFR-R1: the sink never loses a record — a failing neutralization degrades to a placeholder
    def "a neutralization failure degrades to a bounded, control-free placeholder: #failure"() {
        given:
        def broken = new SafeMessageConverter({
            throw failure
        } as UnaryOperator)

        when:
        String rendered = broken.convert(event('anything'))

        then:
        rendered.contains('message')
        rendered.contains(failure.getClass().name)
        InertText.isInert(rendered)
        InertText.isSingleLine(rendered)

        and: 'and the converter is still usable for the next record'
        broken.convert(event('anything')) == rendered

        where:
        failure << [
            new IllegalStateException('pattern engine gave up'),
            new StackOverflowError()
        ]
    }

    private ILoggingEvent event(String formattedMessage) {
        Stub(ILoggingEvent) {
            getFormattedMessage() >> formattedMessage
        }
    }
}
