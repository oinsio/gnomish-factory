package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.port.console.ConsoleIO

/**
 * The production console owner ({@link SystemConsoleIO}) bound to whatever the named process
 * stream is at the moment of each write — so a spec that swaps {@code System.out}/{@code
 * System.err} after building its command still captures what the command prints.
 *
 * <p>Specs build their commands with this rather than with a recording double on purpose: the
 * bytes asserted on are then the ones the shipping owner really wrote, which is what makes the
 * {@code --json} byte-identity claim (UX3 of harden-untrusted-text-sinks) a claim about the
 * shipping path rather than about a fake.
 *
 * <p>Reached through {@code StdoutCaptureFixture.liveConsole()} / {@code liveErrorConsole()}; a
 * class of its own because a Groovy trait may not hold the anonymous stream this needs.
 */
class LiveConsoleIO implements ConsoleIO {

    private final ConsoleIO delegate

    private LiveConsoleIO(OutputStream stream) {
        this.delegate = new SystemConsoleIO(System.in, stream)
    }

    /** The owner over the process's standard output, as the composition root's primary binds it. */
    static LiveConsoleIO onStdout() {
        new LiveConsoleIO(new CurrentStdout())
    }

    /** The owner over the process's standard error, as the composition root's error console binds it. */
    static LiveConsoleIO onStderr() {
        new LiveConsoleIO(new CurrentStderr())
    }

    @Override
    String readLine() {
        delegate.readLine()
    }

    @Override
    void print(String text) {
        delegate.print(text)
    }

    @Override
    void printMachine(String text) {
        delegate.printMachine(text)
    }

    /** An output stream that resolves {@code System.out} per write rather than capturing it. */
    private static class CurrentStdout extends OutputStream {

        @Override
        void write(int b) {
            System.out.write(b)
        }

        @Override
        void write(byte[] b, int off, int len) {
            System.out.write(b, off, len)
        }

        @Override
        void flush() {
            System.out.flush()
        }
    }

    /** The same, for {@code System.err}. */
    private static class CurrentStderr extends OutputStream {

        @Override
        void write(int b) {
            System.err.write(b)
        }

        @Override
        void write(byte[] b, int off, int len) {
            System.err.write(b, off, len)
        }

        @Override
        void flush() {
            System.err.flush()
        }
    }
}
