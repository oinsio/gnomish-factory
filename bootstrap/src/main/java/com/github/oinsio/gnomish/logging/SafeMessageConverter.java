package com.github.oinsio.gnomish.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.function.UnaryOperator;

/**
 * The {@code %safeMsg} conversion word: the sink-side backstop for the formatted message (FR1 of
 * harden-untrusted-text-sinks). Whatever the call site assembled — an operator report, an exception
 * message read back through {@code getMessage()}, a record's {@code toString()} — the bytes that
 * reach the encoder are stripped of escape sequences, rendered on one line and bounded, because
 * this layer does not depend on caller discipline. Correct call sites see no change: the
 * neutralization is idempotent over the choke point's own output (FR2).
 *
 * <p>Replaces the plain {@code %msg} in {@code GNOMISH_LOG_PATTERN}; both Logback files register it
 * by {@code <conversionRule>} and {@code LogbackConfigSpec} holds them to it.
 *
 * <p>Implements FR1, FR2, NFR-R1 of harden-untrusted-text-sinks.
 */
public class SafeMessageConverter extends ClassicConverter {

    private final UnaryOperator<String> neutralizer;

    /** The constructor Logback's {@code <conversionRule>} calls: the production neutralization. */
    public SafeMessageConverter() {
        this(SinkNeutralizer::oneLine);
    }

    /**
     * The seam NFR-R1 is exercised through: a spec injects a neutralization that throws and asserts
     * the converter still yields a record.
     *
     * @param neutralizer what turns a formatted message into the rendered one; never null
     */
    SafeMessageConverter(UnaryOperator<String> neutralizer) {
        this.neutralizer = neutralizer;
    }

    @Override
    public String convert(ILoggingEvent event) {
        try {
            return neutralizer.apply(event.getFormattedMessage());
        } catch (RuntimeException | StackOverflowError failure) {
            return SinkNeutralizer.placeholder("message", failure);
        }
    }
}
