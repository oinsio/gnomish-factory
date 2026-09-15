package com.github.oinsio.gnomish.logging;

import ch.qos.logback.classic.pattern.MDCConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.function.UnaryOperator;

/**
 * The {@code %safeX{key}} conversion word: the sink-side backstop for the MDC values the pattern
 * renders (FR4 of harden-untrusted-text-sinks). A stage name comes from the target repository's own
 * {@code .gnomish/} manifest and a task id from the tracker, so the record's context fields are as
 * attacker-influenced as its message — and they sit ahead of the message on the line, where a
 * forged record prefix would be most convincing.
 *
 * <p>The MDC map itself is left raw (design D4): its values are the grep keys an operator and a
 * spec hold, so neutralizing at the put would make {@code grep 'taskId=<id>'} stop matching the id
 * they have. Only the rendering is neutralized. A key the event does not carry renders as it does
 * today — Logback's own default replacement, the empty string — because this converter delegates
 * the lookup to {@link MDCConverter} and only rewrites what comes back.
 *
 * <p>Implements FR4, NFR-R1 of harden-untrusted-text-sinks.
 */
public class SafeMdcConverter extends MDCConverter {

    private final UnaryOperator<String> neutralizer;

    /** The constructor Logback's {@code <conversionRule>} calls: the production neutralization. */
    public SafeMdcConverter() {
        this(SinkNeutralizer::oneLine);
    }

    /**
     * The seam NFR-R1 is exercised through: a spec injects a neutralization that throws and asserts
     * the converter still yields a value.
     *
     * @param neutralizer what turns a raw MDC value into the rendered one; never null
     */
    SafeMdcConverter(UnaryOperator<String> neutralizer) {
        this.neutralizer = neutralizer;
    }

    @Override
    public String convert(ILoggingEvent event) {
        try {
            return neutralizer.apply(super.convert(event));
        } catch (RuntimeException | StackOverflowError failure) {
            return SinkNeutralizer.placeholder("MDC value", failure);
        }
    }
}
