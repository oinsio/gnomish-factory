package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

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
 * <p>Both streams are {@link UntrustedText}, exactly as {@code GitCommandResult}'s are: docker
 * speaks for image authors, for container labels and for whatever a gnome made its box print, so
 * its output is attacker-influenced whichever stream it arrives on (design D3, D11 of
 * type-untrusted-text). Stderr reaches logs and reports through an exit; stdout is read by this
 * family's parsers through {@code forParsing()}, which hands over the bytes as docker wrote them —
 * a capped, flattened rendering would break every {@code inspect} parse.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.adapter.git.GitCommandResult}: both mint
 * their subprocess family's two streams as {@link UntrustedText} through the same
 * three/four-argument {@code of} factory shape, defaulting to {@link Termination#EXITED}. They
 * stay separate types because git additionally credential-scrubs stderr; only that factory shape
 * and the untrusted-text minting must stay aligned.
 *
 * <p>Implements FR3, NFR-R1 of add-sandbox-core; FR6, FR10 of
 * bound-subprocess-commands; FR1, FR7 of type-untrusted-text.
 *
 * @param exitCode the docker process's exit code; docker's own only when {@code termination} is
 *     {@link Termination#EXITED}
 * @param stdout the process's standard output, captured in full on a normal exit
 * @param stderr the process's standard error, captured in full on a normal exit
 * @param termination how the invocation ended
 */
record DockerResult(int exitCode, UntrustedText stdout, UntrustedText stderr, Termination termination) {

    /**
     * Captures one invocation's streams: the single place docker's text becomes untrusted text.
     * It lives here rather than at {@link DockerCli}'s capture so a result built anywhere else —
     * a spec, a scripted stand-in — cannot hold an unminted string, which is the escape hatch a
     * {@code String}-typed constructor would leave open ({@code implementation.md}, item 3).
     *
     * @param exitCode the docker process's exit code
     * @param stdout the captured standard output
     * @param stderr the captured standard error
     * @param termination how the invocation ended
     * @return the result, with both streams minted as subprocess output
     */
    static DockerResult of(int exitCode, String stdout, String stderr, Termination termination) {
        return new DockerResult(
                exitCode, UntrustedText.subprocess(stdout), UntrustedText.subprocess(stderr), termination);
    }

    /**
     * A result for a command that ran to completion — the shape every caller and
     * every scripted docker stand-in builds.
     *
     * @param exitCode the docker process's exit code
     * @param stdout the process's standard output
     * @param stderr the process's standard error
     * @return the result, with both streams minted as subprocess output
     */
    static DockerResult of(int exitCode, String stdout, String stderr) {
        return of(exitCode, stdout, stderr, Termination.EXITED);
    }

    /**
     * Whether the command ran to completion and exited zero. A command that was
     * killed on its deadline is not ok, whatever the OS recorded for it.
     */
    boolean ok() {
        return termination == Termination.EXITED && exitCode == 0;
    }
}
