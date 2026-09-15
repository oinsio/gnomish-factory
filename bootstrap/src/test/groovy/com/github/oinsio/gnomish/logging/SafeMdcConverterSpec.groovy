package com.github.oinsio.gnomish.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.logtext.LogText
import com.github.oinsio.gnomish.testsupport.AdversarialCorpus
import com.github.oinsio.gnomish.testsupport.InertText
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR4, NFR-R1 of harden-untrusted-text-sinks, design D4: the `%safeX{key}` conversion word covers
 * the context fields. A stage name is read out of the target repository's own `.gnomish/` manifest
 * and a task id out of the tracker, so they are as attacker-influenced as the message — and they
 * sit ahead of it on the line, where a forged record prefix would be most convincing.
 *
 * <p>The MDC map itself stays raw (D4): its values are the grep keys an operator holds, so the
 * neutralization belongs to the rendering alone.
 */
class SafeMdcConverterSpec extends Specification {

    // FR4: a hostile manifest stage name renders on one line with nothing left to execute
    def "a hostile MDC value renders inertly and single-line: #shape"() {
        when:
        String rendered = converter('stage').convert(event(stage: hostile))

        then:
        InertText.isInert(rendered)
        InertText.isSingleLine(rendered)
        rendered.length() <= LogText.RECORD_CAP_CHARS

        where:
        shape << AdversarialCorpus.ENTRIES.keySet()
        hostile << AdversarialCorpus.ENTRIES.values()
    }

    // FR4: the grep key an operator holds is what the line carries — a clean value is untouched
    def "a value with nothing to neutralize renders as the map holds it"() {
        expect:
        converter('taskId').convert(event(taskId: 'GNOME-17')) == 'GNOME-17'
    }

    // FR4: a key the event does not carry renders as it does today
    def "a missing key renders empty"() {
        expect:
        converter('component').convert(event(taskId: 'GNOME-17')) == ''
    }

    // NFR-R1: the sink never loses a record — a failing neutralization degrades to a placeholder
    def "a neutralization failure degrades to a bounded, control-free placeholder: #failure"() {
        given:
        def broken = new SafeMdcConverter({ throw failure } as UnaryOperator)
        broken.optionList = ['stage']
        broken.start()

        when:
        String rendered = broken.convert(event(stage: 'build'))

        then:
        rendered.contains('MDC value')
        rendered.contains(failure.getClass().name)
        InertText.isInert(rendered)
        InertText.isSingleLine(rendered)

        and: 'and the converter is still usable for the next record'
        broken.convert(event(stage: 'build')) == rendered

        where:
        failure << [
            new IllegalStateException('pattern engine gave up'),
            new StackOverflowError()
        ]
    }

    private static SafeMdcConverter converter(String key) {
        def converter = new SafeMdcConverter()
        converter.optionList = [key]
        converter.start()
        converter
    }

    private ILoggingEvent event(Map<String, String> mdc) {
        Stub(ILoggingEvent) {
            getMDCPropertyMap() >> mdc
        }
    }
}
