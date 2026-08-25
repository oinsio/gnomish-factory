package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.subprocess.Termination;

/**
 * The outcome of one {@code docker} management subprocess invocation (network,
 * volume, container, and inspect commands): exit code and stdout/stderr captured
 * as separate UTF-8 strings, mirroring {@code GitCommandResult} for the git
 * binary (design D2 — Docker CLI as a subprocess, like git).
 *
 * <p>A non-zero {@link #exitCode()} is a normal, expected outcome — callers
 * decide per command what it means (a {@code rm} of an already-gone object is a
 * benign non-zero, a failed {@code run} is fatal). This type never represents
 * "the runtime is unavailable"; that case is a thrown {@link
 * DockerUnavailableException} instead, so a daemon outage is never mistaken for
 * a command that ran and failed (NFR-R1).
 *
 * <p>{@link #termination()} says whether the command ran to completion at all: a
 * command killed on its deadline or on an interrupt never chose its exit code,
 * and a caller that reads only {@link #exitCode()} would take the OS's signal
 * code for docker's own verdict. It is the fourth component precisely so every
 * existing construction site keeps compiling against the three-argument form,
 * which defaults it to {@link Termination#EXITED} (design D6, D11).
 *
 * <p>Implements FR3, NFR-R1 of add-sandbox-core; FR6, FR10 of
 * bound-subprocess-commands.
 *
 * @param exitCode the docker process's exit code; docker's own only when {@code termination} is
 *     {@link Termination#EXITED}
 * @param stdout the process's standard output, captured in full on a normal exit
 * @param stderr the process's standard error, captured in full on a normal exit
 * @param termination how the invocation ended
 */
record DockerResult(int exitCode, String stdout, String stderr, Termination termination) {

    /**
     * A result for a command that ran to completion — the shape every caller and
     * every scripted docker stand-in builds.
     *
     * @param exitCode the docker process's exit code
     * @param stdout the process's standard output
     * @param stderr the process's standard error
     */
    DockerResult(int exitCode, String stdout, String stderr) {
        this(exitCode, stdout, stderr, Termination.EXITED);
    }

    /**
     * Whether the command ran to completion and exited zero. A command that was
     * killed on its deadline is not ok, whatever the OS recorded for it.
     */
    boolean ok() {
        return termination == Termination.EXITED && exitCode == 0;
    }
}
