package com.github.oinsio.gnomish.app.console;

import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.logtext.LogText;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The real {@link ConsoleIO}: reads lines from an {@link InputStream} (normally
 * {@code System.in}, piped stdin included — no TTY required, FR13) and writes to
 * an {@link OutputStream} (normally {@code System.out}).
 *
 * <p>The console owner (FR5, FR6 of harden-untrusted-text-sinks): the one class in
 * production code that writes to the process streams, and therefore the one place the
 * two paths are told apart. {@link #print} renders through {@link LogText#forConsole} —
 * the console exit from the one character table {@code :logtext} owns, so this class
 * holds no character vocabulary of its own — while {@link #printMachine} writes verbatim.
 *
 * <p>Implements FR13 of add-manual-run, FR5 of harden-untrusted-text-sinks.
 */
public final class SystemConsoleIO implements ConsoleIO {

    private final BufferedReader reader;
    private final PrintStream writer;

    public SystemConsoleIO(InputStream in, OutputStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    @Override
    public String readLine() {
        String line;
        try {
            line = reader.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (line == null) {
            throw new ConsoleClosedException();
        }
        return line;
    }

    @Override
    public void print(String text) {
        writer.print(LogText.forConsole(text));
    }

    @Override
    public void printMachine(String text) {
        writer.print(text);
    }
}
