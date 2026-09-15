package com.github.oinsio.gnomish.app.port.console.fake

import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException
import com.github.oinsio.gnomish.app.port.console.ConsoleIO

/**
 * A scripted {@link ConsoleIO}: constructed with a fixed list of lines returned
 * in order by {@link #readLine}, raising {@link ConsoleClosedException} once the
 * script is exhausted (simulated EOF). Every call to {@link #print} and
 * {@link #printMachine} is recorded in {@link #printed} in order for later assertions,
 * with the machine-readable calls also recorded on their own so a spec can pin which
 * path a site chose (FR5 of harden-untrusted-text-sinks).
 *
 * <p>Test fake for the add-manual-run ports; not production code, never
 * PIT-mutated.
 */
class ScriptedConsoleIO implements ConsoleIO {

    private final List<String> script = []

    /** Every line printed on either path, in call order, for later assertions. */
    final List<String> printed = []

    /** Every line printed on the machine-readable path only, in call order. */
    final List<String> printedMachine = []

    ScriptedConsoleIO(List<String> script = []) {
        this.script.addAll(script)
    }

    @Override
    String readLine() {
        if (script.isEmpty()) {
            throw new ConsoleClosedException()
        }
        script.removeFirst()
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
