package com.github.oinsio.gnomish.app.port.console;

/**
 * The single choke point for reading from and writing to the human operator.
 * Deliberately dumb: no knowledge of meta-commands, dialogs, or prompts — that
 * belongs to wrappers built on top (design D1). {@link #readLine()} returns the
 * next line or throws {@link ConsoleClosedException} on EOF, never null and
 * never blocking forever on exhausted input (FR13, NFR-R1).
 *
 * <p>Two write paths, because the terminal has two kinds of reader (FR5 of
 * harden-untrusted-text-sinks). {@link #print} is the human one: its text may carry
 * tracker titles, agent output and subprocess stderr, so the implementation renders
 * anything a terminal would obey visibly rather than passing it on.
 * {@link #printMachine} is the other: {@code --json} output whose reader is a parser,
 * written byte for byte. Together they are the single owner of the process streams —
 * no production class writes to {@code System.out}/{@code System.err} itself (FR6).
 *
 * <p>Implements FR13 of add-manual-run, FR5 of harden-untrusted-text-sinks.
 */
public interface ConsoleIO {

    /**
     * The line terminator a call site appends when it wants its text to end a line. This port is
     * deliberately dumb about line structure — neither write path adds a terminator — so the
     * sites that used to call {@code System.out.println} say so with this constant instead of
     * each spelling a literal. Always {@code \n}, never the platform separator: a carriage
     * return is one of the characters the human path renders visibly, which is right for
     * untrusted text and would be wrong here.
     */
    String LINE_END = "\n";

    /**
     * Reads the next line of input.
     *
     * @return the line read, without its line terminator
     * @throws ConsoleClosedException if the underlying stream is at EOF
     */
    String readLine();

    /**
     * Writes {@code text} to the operator for a person to read, with no added terminator.
     * Anything a terminal would obey — escape sequences, the C1 controls, the
     * direction overrides — is rendered visibly, so the operator sees the attempt as
     * text; line structure and length are preserved, because a report is long by design.
     *
     * @param text the text to write
     */
    void print(String text);

    /**
     * Writes {@code text} to the operator byte for byte, with no added terminator and no
     * rendering at all: the machine-readable path, for {@code --json} output and anything
     * else a parser rather than a terminal consumes.
     *
     * @param text the text to write
     */
    void printMachine(String text);
}
