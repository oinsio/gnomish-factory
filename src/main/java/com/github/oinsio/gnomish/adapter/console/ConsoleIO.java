package com.github.oinsio.gnomish.adapter.console;

/**
 * The single choke point for reading from and writing to the human operator.
 * Deliberately dumb: no knowledge of meta-commands, dialogs, or prompts — that
 * belongs to wrappers built on top (design D1). {@link #readLine()} returns the
 * next line or throws {@link ConsoleClosedException} on EOF, never null and
 * never blocking forever on exhausted input (FR13, NFR-R1).
 *
 * <p>Implements FR13 of add-manual-run.
 */
public interface ConsoleIO {

    /**
     * Reads the next line of input.
     *
     * @return the line read, without its line terminator
     * @throws ConsoleClosedException if the underlying stream is at EOF
     */
    String readLine();

    /**
     * Writes {@code text} to the operator, verbatim, with no added terminator.
     *
     * @param text the text to write
     */
    void print(String text);
}
