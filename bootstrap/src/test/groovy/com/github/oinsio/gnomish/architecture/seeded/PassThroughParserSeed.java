package com.github.oinsio.gnomish.architecture.seeded;

import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * A class wearing {@code @UntrustedParser} that parses nothing: it hands the captured text back as
 * a plain string, which is the escape hatch type-untrusted-text exists to close (FR10, design D11).
 * Rule (a2)'s source half fails on it and names the method, so the annotation cannot become the
 * laundering hatch that keeping it out of {@code @UntrustedExit} was meant to prevent.
 */
@UntrustedParser
public final class PassThroughParserSeed {

    private PassThroughParserSeed() {}

    /**
     * Returns the captured text unchanged — a parser in name only.
     *
     * @param result the capture record to read; never null
     * @return the captured text, unconverted and ungated
     */
    public static String launder(CarrierAccessorSeed result) {
        return result.stderr().forParsing();
    }
}
