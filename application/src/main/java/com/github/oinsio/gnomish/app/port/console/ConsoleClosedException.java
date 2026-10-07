package com.github.oinsio.gnomish.app.port.console;

/**
 * Signals that {@link ConsoleIO#readLine()} hit EOF: the human's input is
 * exhausted (piped stdin ran out, or the operator closed the stream). Deliberate
 * and detectable rather than a hang or a null return (FR13, NFR-R1). Since {@code run}
 * stopped reading stdin (design D4 of make-run-headless) the one production reader is the
 * takeover confirmation of {@code take}, which treats it as a declining answer.
 *
 * <p>Implements FR13 of add-manual-run.
 */
public class ConsoleClosedException extends RuntimeException {

    public ConsoleClosedException() {
        super("console input exhausted (EOF)");
    }
}
