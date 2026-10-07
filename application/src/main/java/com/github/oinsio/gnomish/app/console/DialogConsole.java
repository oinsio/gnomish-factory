package com.github.oinsio.gnomish.app.console;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;

/**
 * The runner's output owner: wraps a dumb {@link ConsoleIO} and offers its two write paths —
 * {@link #print} for a person at a terminal, {@link #printMachine} for a parser (FR5 of
 * harden-untrusted-text-sinks). It has no read side: a {@code run} never waits on its operator,
 * so a stop is rendered and the process exits with the command that resumes it (design D4 of
 * make-run-headless).
 *
 * <p>Implements FR3 of add-manual-run; FR5 of harden-untrusted-text-sinks; D4 of
 * make-run-headless.
 */
public final class DialogConsole {

    private final ConsoleIO io;

    /**
     * @param io the console I/O to wrap
     */
    public DialogConsole(ConsoleIO io) {
        this.io = io;
    }

    /**
     * Writes {@code text} to the operator on the human path of the wrapped {@link ConsoleIO} —
     * a thin passthrough for blocks of text such as a stage briefing or a stop render.
     *
     * <p>Implements FR3 of add-manual-run.
     *
     * @param text the text to print
     */
    public void print(String text) {
        io.print(text);
    }

    /**
     * Writes {@code text} to the operator byte for byte — the machine-readable path of the
     * wrapped {@link ConsoleIO}, for blocks a parser rather than a terminal consumes
     * (FR5 of harden-untrusted-text-sinks). Kept beside {@link #print} so a holder of this
     * wrapper never has to reach past it for one of the two paths.
     *
     * @param text the text to print
     */
    public void printMachine(String text) {
        io.printMachine(text);
    }
}
