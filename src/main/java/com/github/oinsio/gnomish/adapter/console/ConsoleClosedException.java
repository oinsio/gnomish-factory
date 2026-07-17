package com.github.oinsio.gnomish.adapter.console;

/**
 * Signals that {@link ConsoleIO#readLine()} hit EOF: the human's input is
 * exhausted (piped stdin ran out, or the operator closed the stream). Deliberate
 * and detectable rather than a hang or a null return (FR13, NFR-R1); flows
 * through the engine as an ordinary infrastructure failure (design D2).
 *
 * <p>Implements FR13 of add-manual-run.
 */
public class ConsoleClosedException extends RuntimeException {

    public ConsoleClosedException() {
        super("console input exhausted (EOF)");
    }
}
