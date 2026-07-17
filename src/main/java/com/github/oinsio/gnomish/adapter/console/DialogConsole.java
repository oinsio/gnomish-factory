package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.status.Activity;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The single input choke point (design D1): wraps a dumb {@link ConsoleIO},
 * intercepting the {@code status} / {@code status --json} meta-commands below
 * every interactive adapter and runner dialog so none of them has to know
 * about {@code status}. Every prompt in the manual-run dialog SHALL go through
 * this class rather than {@link ConsoleIO} directly.
 *
 * <p>The engine has no notion of a human being prompted — only this class,
 * the single choke point, knows a prompt is pending — so every blocking read
 * marks {@link Activity.AwaitingInput} on the wrapped {@link ActivityTracker}
 * just before reading and restores the prior activity immediately after,
 * whether the read returns a line or raises {@link ConsoleClosedException} on
 * EOF (design D7).
 *
 * <p>Implements FR10, FR13, UX1, D7 of add-manual-run.
 */
public final class DialogConsole {

    private static final String STATUS_COMMAND = "status";
    private static final String STATUS_JSON_COMMAND = "status --json";

    private final ConsoleIO io;
    private final StatusRenderer statusRenderer;
    private final ActivityTracker activityTracker;
    private boolean inputExhausted;

    /**
     * Convenience constructor for call sites with no live-activity signal to
     * report, using {@link ActivityTracker#NONE}.
     *
     * @param io the console I/O to wrap
     * @param statusRenderer the {@code status} meta-command renderer
     */
    public DialogConsole(ConsoleIO io, StatusRenderer statusRenderer) {
        this(io, statusRenderer, ActivityTracker.NONE);
    }

    /**
     * @param io the console I/O to wrap
     * @param statusRenderer the {@code status} meta-command renderer
     * @param activityTracker marks/restores {@code AWAITING_INPUT} around each
     *     blocking read (FR10, D7)
     */
    public DialogConsole(ConsoleIO io, StatusRenderer statusRenderer, ActivityTracker activityTracker) {
        this.io = io;
        this.statusRenderer = statusRenderer;
        this.activityTracker = activityTracker;
    }

    /**
     * Whether the underlying {@link ConsoleIO} has hit EOF during a previous
     * prompt on this console. Latched permanently once set; callers (the
     * runner) consult it to skip resume/checkpoint dialogs rather than
     * re-entering an input-requiring prompt on exhausted input (FR13).
     *
     * @return {@code true} once a {@link ConsoleClosedException} has propagated
     *     from a prompt on this console
     */
    public boolean inputExhausted() {
        return inputExhausted;
    }

    /**
     * Writes {@code text} to the operator verbatim, with no prompt and no
     * response expected — a thin passthrough to the wrapped {@link ConsoleIO}
     * for blocks of text (such as the stage briefing, task 5.2) that precede a
     * prompt rather than being one themselves.
     *
     * <p>Implements FR3 of add-manual-run.
     *
     * @param text the text to print
     */
    public void print(String text) {
        io.print(text);
    }

    /**
     * Prints {@code prompt} and reads one line, intercepting {@code status} and
     * {@code status --json} (FR10): a meta-command renders the current status,
     * prints it, and re-prompts with the same {@code prompt} text — the caller
     * never sees the meta-command. On EOF, the input-exhausted flag latches and
     * {@link ConsoleClosedException} propagates to the caller (FR13).
     *
     * <p>Implements FR10, FR13 of add-manual-run.
     *
     * @param prompt the prompt text to print before reading
     * @return the first non-meta-command line the operator enters
     * @throws ConsoleClosedException if input is exhausted before a non-meta
     *     line is read
     */
    public String prompt(String prompt) {
        while (true) {
            io.print(prompt);
            String line = readLine(prompt);
            if (STATUS_COMMAND.equals(line)) {
                io.print(statusRenderer.render(false));
                continue;
            }
            if (STATUS_JSON_COMMAND.equals(line)) {
                io.print(statusRenderer.render(true));
                continue;
            }
            return line;
        }
    }

    /**
     * Prompts for one of a fixed set of {@code acceptedAnswers}, re-prompting
     * with the accepted answers listed whenever the operator's line matches
     * none of them (UX1), while every line still passes through meta-command
     * interception first (FR10). Later interactive adapters (tasks 5.1-5.4)
     * build their pass/fail/running and other fixed-answer prompts on this
     * helper instead of duplicating the re-prompt loop.
     *
     * <p>Implements FR10, UX1 of add-manual-run.
     *
     * @param prompt the prompt text to print before each read
     * @param acceptedAnswers the exact lines that are accepted; order is
     *     preserved when listing them back to the operator
     * @return the first line that matches one of {@code acceptedAnswers}
     * @throws ConsoleClosedException if input is exhausted before an accepted
     *     line is read
     */
    public String ask(String prompt, Collection<String> acceptedAnswers) {
        Set<String> accepted = new LinkedHashSet<>(acceptedAnswers);
        String currentPrompt = prompt;
        while (true) {
            String answer = prompt(currentPrompt);
            if (accepted.contains(answer)) {
                return answer;
            }
            currentPrompt = "Unrecognized answer. Accepted answers: " + String.join(", ", accepted) + ". " + prompt;
        }
    }

    private String readLine(String prompt) {
        Activity previousActivity = activityTracker.markAwaitingInput(prompt);
        try {
            return io.readLine();
        } catch (ConsoleClosedException e) {
            inputExhausted = true;
            throw e;
        } finally {
            activityTracker.restore(previousActivity);
        }
    }
}
