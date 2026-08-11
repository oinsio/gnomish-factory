package com.github.oinsio.gnomish.adapter.environment;

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
 * <p>Implements FR3, NFR-R1 of add-sandbox-core.
 *
 * @param exitCode the docker process's exit code
 * @param stdout the process's standard output, captured in full
 * @param stderr the process's standard error, captured in full
 */
record DockerResult(int exitCode, String stdout, String stderr) {

    /** Whether the command exited zero. */
    boolean ok() {
        return exitCode == 0;
    }
}
