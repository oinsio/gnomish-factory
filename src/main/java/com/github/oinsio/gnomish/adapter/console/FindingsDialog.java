package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.domain.engine.Finding;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared findings-entry dialog (design D1): prompts the operator for one
 * finding message per line, stopping on an empty line. Both the interactive
 * {@code ExternalCheckClient} (task 5.3) and {@code JudgeVoter} (task 5.4)
 * call this when the operator reports a failing verdict — humans supply only
 * a message per finding, so {@link Finding#location()} and
 * {@link Finding#details()} are always {@code null}.
 *
 * <p>Implements FR4, FR5 of add-manual-run.
 */
public final class FindingsDialog {

    private static final String PROMPT = "Finding (empty line to finish): ";

    /**
     * Repeatedly prompts {@code console} for one finding message per line,
     * building a {@link Finding} per non-empty line, until an empty line is
     * entered. The {@code status} / {@code status --json} meta-commands are
     * transparently handled by {@link DialogConsole#prompt} and never end
     * collection or become a finding.
     *
     * @param console the dialog console to prompt on
     * @return the collected findings, in the order entered; empty if the
     *     first line is empty
     * @throws ConsoleClosedException if input is exhausted before an empty
     *     line is read
     */
    public List<Finding> collect(DialogConsole console) {
        List<Finding> findings = new ArrayList<>();
        while (true) {
            String line = console.prompt(PROMPT);
            if (line.isEmpty()) {
                return findings;
            }
            findings.add(new Finding(line, null, null));
        }
    }
}
