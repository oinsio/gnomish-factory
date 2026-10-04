package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import org.jspecify.annotations.NullMarked;

/**
 * {@code gnomish --version}: the one command line besides the empty one that reaches no subcommand
 * parser (design D3 of add-release-pipeline; the {@code cli-arguments} capability, "Unknown options
 * are usage errors").
 *
 * <p>Answered by the entry point before Spring starts, so printing the version resolves no
 * project, loads no configuration and opens no log file — the release workflow runs it from an
 * unpacked archive with nothing registered (FR8). Only the <em>sole</em> token qualifies: {@code
 * --version} beside any other token is left to the {@code run} parser, which rejects it as an
 * unknown option like any other, so this class cannot become a way past that check.
 *
 * <p>The version comes from {@link FactoryVersion}, its one runtime reader, and leaves through the
 * console owner's human path.
 *
 * <p>Implements FR6 of add-release-pipeline.
 */
// Null-marked explicitly: this module carries no package-info (see ManualRunRunner).
@NullMarked
public final class VersionCommand {

    /** The one token this command answers to. */
    static final String TOKEN = "--version";

    private VersionCommand() {}

    /**
     * Prints the factory version on one line when {@code args} is exactly the sole {@code
     * --version} token.
     *
     * @param args the raw command line
     * @param console where the version line is written
     * @return {@code true} when the version was printed and the process is done; {@code false},
     *     with nothing printed, for every other command line
     */
    public static boolean answer(String[] args, ConsoleIO console) {
        if (args.length != 1 || !TOKEN.equals(args[0])) {
            return false;
        }
        console.print(FactoryVersion.current().value() + ConsoleIO.LINE_END);
        return true;
    }
}
