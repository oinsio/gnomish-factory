package com.github.oinsio.gnomish.app.port.console.fake

import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException
import com.github.oinsio.gnomish.app.port.console.ConsoleIO

/**
 * An output-capturing {@link ConsoleIO}: every call to {@link #print} and {@link #printMachine}
 * is recorded in {@link #printed} in order for later assertions, with the machine-readable calls
 * also recorded on their own so a spec can pin which path a site chose (FR5 of
 * harden-untrusted-text-sinks). It holds no input: {@link #readLine} always raises
 * {@link ConsoleClosedException}, the simulated EOF, because no {@code run} path reads the
 * console any more (design D4 of make-run-headless). A spec proving a path never reads
 * overrides {@link #readLine} to fail outright.
 *
 * <p>Test fake for the add-manual-run ports; not production code, never PIT-mutated.
 */
class ScriptedConsoleIO implements ConsoleIO {

    /** Every line printed on either path, in call order, for later assertions. */
    final List<String> printed = []

    /** Every line printed on the machine-readable path only, in call order. */
    final List<String> printedMachine = []

    @Override
    String readLine() {
        throw new ConsoleClosedException()
    }

    @Override
    void print(String text) {
        printed << text
    }

    @Override
    void printMachine(String text) {
        printed << text
        printedMachine << text
    }
}
