package com.github.oinsio.gnomish.subprocess;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The one way a supervised process is started (design D16 of fix-terminal-receipt-convergence): a
 * reader of a child's output takes this role by constructor and never calls {@link
 * ProcessBuilder#start()} itself, so a spec hands in a fake process whose exit and pipe ends it
 * gates instead of a script whose timing becomes the spec's premise.
 *
 * <p>The module has exactly one production form, {@link #grouped()}: there is no plain form, since
 * every host process — the {@code docker} client of a box command included — is launched into its
 * own process group so the group sweep can reach a descendant the parent already lost (design D19,
 * FR21, FR22). A short constructor that supplies {@link #grouped()} is a Local Default of this
 * module ({@code process-invariants.md}, clause 2), admissible because no second production form
 * exists to default away from. {@code LaunchOwnerGateSpec} in {@code :bootstrap} keeps the owner
 * single: a process builder is started only in the owner's file, and nothing else in production
 * implements this interface.
 *
 * <p>The child's standard input is set here too, from the caller's {@link StdinSource}, and only
 * here (design D20): there is no start without a source, so a launch cannot leave the JDK's default
 * pipe on the child's stdin for the factory to write into and a descendant to hold. {@code
 * LaunchOwnerGateSpec} keeps {@code getOutputStream()} and {@code redirectInput(} out of every other
 * production source.
 *
 * <p>Implements FR18, FR21, FR22, FR23 of fix-terminal-receipt-convergence.
 */
public interface ProcessLaunch {

    /**
     * Starts the command {@code builder} describes, its standard input taken from {@code stdin}.
     *
     * @param builder the fully configured command — environment, working directory and the output
     *     redirects are the caller's; its input redirect is the owner's, set from {@code stdin}
     * @param stdin what the child reads: {@link StdinSource#NONE}, or the whole of its input
     * @return the started process
     * @throws IOException if the binary could not be launched, or the input could not be written
     *     before it — the JDK's own exception, since what an unlaunchable binary means is the
     *     caller's classification
     */
    Process start(ProcessBuilder builder, StdinSource stdin) throws IOException;

    /**
     * Whether a finished invocation is the launcher's refusal to exec its program — a launch failure
     * the waiter classifies as the caller's own {@link IOException} would be, never as the
     * program's result (design D19 as amended 2026-10-17, twice). The launch resolves the
     * executable and its interpreter chain before the launcher is in front, so this is the residue
     * only: a {@code noexec} mount, {@code ETXTBSY}, a race with the exec. Judged by the exit code
     * launchers give it, 126 or 127, together with the launcher's own line on stderr — the perl
     * launcher's {@code gnomish-launch: cannot exec}, or util-linux's {@code failed to execute}; a
     * program that itself exits 127 is a result. Asked only by a waiter that captures stderr whole;
     * a streaming waiter carries the line in its stderr or output tail instead.
     *
     * @param termination how the invocation ended; only an exit is a launcher's refusal
     * @param exitCode its exit code
     * @param stderr what it wrote on stderr, as captured
     * @return {@code true} when the launcher, not the program, ended it
     */
    static boolean launcherCouldNotExec(Termination termination, int exitCode, String stderr) {
        return HostLauncher.couldNotExec(termination, exitCode, stderr);
    }

    /**
     * The module's one production form: the host process in its own process group, its input file
     * in {@code java.io.tmpdir} — the Local Default the short constructors supply.
     *
     * @return the grouped launch
     */
    static ProcessLaunch grouped() {
        return grouped(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /**
     * The module's one production form, with the directory a child's input file lives in for the
     * length of its launch.
     *
     * @param inputDirectory a writable directory for the owner-only input files
     * @return the grouped launch
     */
    static ProcessLaunch grouped(Path inputDirectory) {
        return new GroupedLaunch(inputDirectory);
    }
}
