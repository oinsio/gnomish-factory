package com.github.oinsio.gnomish.adapter.console;

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
 * <p>Implements FR13 of add-manual-run.
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
        writer.print(text);
    }
}
