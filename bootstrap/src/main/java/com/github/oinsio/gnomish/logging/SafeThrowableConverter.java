package com.github.oinsio.gnomish.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.CoreConstants;
import com.github.oinsio.gnomish.logtext.LogText;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The {@code %safeEx} conversion word: the sink-side backstop for the rendered throwable (FR3 of
 * harden-untrusted-text-sinks). An exception message is the factory's widest laundering path —
 * 33 constructor sites fold raw subprocess stderr into one — and a stack trace is the one part of a
 * record that is legitimately many lines, so the message's own line break lands at column 0 of the
 * log looking exactly like the start of a new event.
 *
 * <p>Flattening the trace the way {@link SafeMessageConverter} flattens a message would destroy the
 * diagnosis ADR 0004 exists to keep, so this converter rewrites Logback's own rendering line by
 * line instead (design D3): each line is stripped, and every line that is not one of the shapes
 * Logback's stack-trace rendering produces is written behind a visible continuation marker. A
 * forged record prefix therefore arrives indented and marked, never at column 0 — CERT IDS03-J's
 * second compliant solution. An unrecognized line shape degrades to a continuation line, which is
 * the safe direction when a future Logback release adds one.
 *
 * <p>The text is split on line breaks before anything else, so every separator — carriage return
 * and the Unicode line/paragraph separators included — becomes a marked continuation rather than
 * surviving inside a line. The whole rewriting is then bounded by {@link LogText#capRecord}: a
 * hostile message can be a megabyte, and the bound is what FR1 asks of every record.
 *
 * <p>Implements FR3, NFR-R1 of harden-untrusted-text-sinks.
 */
public class SafeThrowableConverter extends ThrowableProxyConverter {

    /**
     * The shapes Logback's stack-trace rendering produces, which keep their own indentation. The
     * frame form requires at least one tab, because that is what {@code ThrowableProxyUtil} writes
     * and a message line claiming to be a frame must not be able to reach column 0; the nesting
     * forms accept the tabs Logback prepends per cause-chain level and, at the top level, none.
     * Data, not code: a release that adds a shape adds a row here, and until it does the new shape
     * renders as a continuation line.
     */
    private static final List<Pattern> TRACE_LINES = List.of(
            Pattern.compile("^\\t+at .*"),
            Pattern.compile("^\\t*(?:Caused by|Suppressed|Wrapped by): .*"),
            Pattern.compile("^\\t*\\.\\.\\. \\d+ (?:more|common frames omitted)$"));

    /**
     * What every line that is not a recognized trace line is written behind: a tab, a bar and a
     * space, so the reader sees a continuation of the record above and column 0 stays the exclusive
     * property of the timestamp a real record starts with.
     */
    private static final String CONTINUATION = "\t| ";

    /** Every line break, so a separator becomes a line of its own rather than surviving in one. */
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private final UnaryOperator<String> lineNeutralizer;

    /** The constructor Logback's {@code <conversionRule>} calls: the production neutralization. */
    public SafeThrowableConverter() {
        this(LogText::strip);
    }

    /**
     * The seam NFR-R1 is exercised through: a spec injects a neutralization that throws and asserts
     * the converter still yields a rendering.
     *
     * @param lineNeutralizer what makes one line of the rendering inert; never null
     */
    SafeThrowableConverter(UnaryOperator<String> lineNeutralizer) {
        this.lineNeutralizer = lineNeutralizer;
    }

    @Override
    protected String throwableProxyToString(IThrowableProxy proxy) {
        try {
            return rewrite(super.throwableProxyToString(proxy));
        } catch (RuntimeException | StackOverflowError failure) {
            return SinkNeutralizer.placeholder("throwable", failure) + CoreConstants.LINE_SEPARATOR;
        }
    }

    /**
     * Strips each line of {@code rendered} and marks every line that is not a recognized trace
     * line, keeping the first line at column 0 — it is the {@code Class: message} head Logback
     * writes there, and the record it continues is the one on the line above it.
     */
    private String rewrite(String rendered) {
        String[] lines = LINE_BREAK.split(rendered, -1);
        // Logback terminates its rendering with a line separator, so the split's last element is
        // the empty remainder after it rather than a line; writing it would add a blank line per
        // exception. An interior empty line is a line, and keeps its continuation marker.
        int count = lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        StringBuilder out = new StringBuilder(rendered.length());
        for (int i = 0; i < count; i++) {
            String line = lineNeutralizer.apply(lines[i]);
            if (i > 0 && !isTraceLine(line)) {
                out.append(CONTINUATION);
            }
            out.append(line).append(CoreConstants.LINE_SEPARATOR);
        }
        return LogText.capRecord(out.toString());
    }

    private static boolean isTraceLine(String line) {
        return TRACE_LINES.stream().anyMatch(shape -> shape.matcher(line).matches());
    }
}
