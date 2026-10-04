package com.github.oinsio.gnomish;

import com.github.oinsio.gnomish.app.VersionCommand;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import org.jspecify.annotations.NullMarked;

/**
 * The entry point's one decision (design D3 of add-release-pipeline): a sole {@code --version} is
 * answered before Spring starts; every other command line boots the application.
 *
 * <p>It lives apart from {@link FactoryApplication} so that the branch stays inside the mutation
 * gate: {@code FactoryApplication} is excluded from PIT as {@code main()} wiring, and a single
 * {@code if} there would leave this decision unguarded. {@code EntrypointSpec} pins it in process.
 *
 * <p>Implements FR6 of add-release-pipeline.
 */
@NullMarked
final class Entrypoint {

    private Entrypoint() {}

    /**
     * Answers {@code args} as a version request, or runs {@code boot}.
     *
     * @param args the raw command line
     * @param console where a version line is written
     * @param boot starts the application; run only when {@code args} is no version request
     */
    static void start(String[] args, ConsoleIO console, Runnable boot) {
        if (VersionCommand.answer(args, console)) {
            return;
        }
        boot.run();
    }
}
