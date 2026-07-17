package com.github.oinsio.gnomish.adapter.console.fake

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException
import com.github.oinsio.gnomish.adapter.console.ConsoleIO

/**
 * A scripted {@link ConsoleIO}: constructed with a fixed list of lines returned
 * in order by {@link #readLine}, raising {@link ConsoleClosedException} once the
 * script is exhausted (simulated EOF). Every call to {@link #print} is recorded
 * in order for later assertions.
 *
 * <p>Test fake for the add-manual-run ports; not production code, never
 * PIT-mutated.
 */
class ScriptedConsoleIO implements ConsoleIO {

    private final List<String> script = []

    /** Every line printed, in call order, for later assertions. */
    final List<String> printed = []

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
}
