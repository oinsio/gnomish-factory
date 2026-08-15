package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.console.SystemConsoleIO;
import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The production {@link TakeoverConfirmation} (design D9, FR6 of add-claim-heartbeat): it consults
 * a real operator only when a TTY is attached. TTY presence is detected via {@code System.console()
 * != null} — a piped or redirected run has none, so it reports {@link Decision#UNAVAILABLE} and the
 * {@code --takeover} flag becomes the only headless path. When a TTY is present it prints the claim
 * facts and a {@code [y/N]} prompt through a {@link ConsoleIO} and reads one line: {@code y}/{@code
 * yes} (case-insensitive) confirms, anything else — including EOF — declines.
 *
 * <p>Both the TTY probe and the {@link ConsoleIO} are injected suppliers so the class is unit- and
 * mutation-testable without a real terminal; {@link #systemTty()} wires the production pair
 * ({@code System.console()} and a {@link SystemConsoleIO} over the process's own stdin/stdout,
 * matching {@code ManualRunConfiguration#systemConsoleIO}). The {@link ConsoleIO} is built lazily,
 * only when a TTY is confirmed present, so a headless run never touches {@code System.in}.
 *
 * <p>Implements FR6 of add-claim-heartbeat.
 */
record ConsoleTakeoverConfirmation(BooleanSupplier ttyPresent, Supplier<ConsoleIO> consoleFactory)
        implements TakeoverConfirmation {

    /**
     * The production wiring: a real interactive terminal as the TTY probe, {@link SystemConsoleIO}
     * over stdin/stdout. On JDK 22+ {@code System.console()} is non-null even for a piped stream, so
     * the probe is {@code Console#isTerminal()} — true only for an attached terminal. The probe and
     * the console are named methods (not lambdas) so their mutants attribute to the {@link
     * DoNotMutate} methods below rather than to synthetic lambda methods.
     */
    static ConsoleTakeoverConfirmation systemTty() {
        return new ConsoleTakeoverConfirmation(
                ConsoleTakeoverConfirmation::realTerminalAttached, ConsoleTakeoverConfirmation::systemConsole);
    }

    // @DoNotMutate: a real-terminal probe cannot be exercised by a unit test — under a headless test
    // JVM System.console().isTerminal() is always false, and forcing it true would block the run
    // reading real System.in (the exact PIT TIMED_OUT observed). Pure System integration wiring.
    // System.console() != null is deliberately guarded by isTerminal() (the JDK 22+ terminal probe),
    // so the SystemConsoleNull pattern's "don't rely on a null return" concern does not apply.
    @DoNotMutate
    @SuppressWarnings("SystemConsoleNull")
    private static boolean realTerminalAttached() {
        return System.console() != null && System.console().isTerminal();
    }

    // @DoNotMutate: wraps the process's own stdin/stdout; building it in a unit test would bind to
    // real System.in. Pure System integration wiring, exercised only against an actual terminal.
    @DoNotMutate
    private static ConsoleIO systemConsole() {
        return new SystemConsoleIO(System.in, System.out);
    }

    @Override
    public Decision confirm(TaskRef ref, String holder, String lastBeatAge) {
        if (!ttyPresent().getAsBoolean()) {
            return Decision.UNAVAILABLE;
        }
        ConsoleIO console = consoleFactory().get();
        console.print("Task " + ref.id() + " is claimed by " + holder + " (last beat " + lastBeatAge
                + "). Take it over? [y/N] ");
        return isYes(readAnswer(console)) ? Decision.CONFIRMED : Decision.DECLINED;
    }

    /** Reads one line, treating EOF (Ctrl-D at the prompt) as an empty, non-confirming answer. */
    private static String readAnswer(ConsoleIO console) {
        try {
            return console.readLine();
        } catch (ConsoleClosedException closed) {
            return "";
        }
    }

    private static boolean isYes(String answer) {
        String normalized = answer.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
    }
}
