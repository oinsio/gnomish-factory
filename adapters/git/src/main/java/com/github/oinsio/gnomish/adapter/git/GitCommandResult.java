package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.subprocess.Termination;

/**
 * The outcome of one {@code git} subprocess invocation: how the invocation ended, its exit code,
 * and stdout/stderr captured as separate streams (unlike {@code CommandProcessRunner}'s
 * merged-stream approach for shell checks) so callers can parse git plumbing output cleanly while
 * still seeing warnings on stderr.
 *
 * <p>A non-zero {@link #exitCode()} is a normal, expected outcome here — callers (branch creation,
 * commit, push, ...) decide per-command what a given exit code means. This type never represents
 * "the git binary could not be launched"; that case is a thrown {@link GitBinaryNotFoundException}
 * instead.
 *
 * <p>{@link #termination()} is what a caller must read <em>before</em> the exit code, and the
 * reason it exists: a command that was killed on its deadline or interrupted by a shutdown never
 * established a remote outcome at all, so reading its exit code as "git ran and said no" is how a
 * fabricated {@code origin is behind} note reached an operator (design D6). Everything that ran to
 * its own exit is {@link Termination#EXITED}, which the three-argument constructor supplies — the
 * construction sites and specs that predate the bound are unchanged and stay correct (NFR-R3).
 *
 * <p>Implements FR2 of add-git-workflow; FR6, NFR-R3 of bound-subprocess-commands.
 *
 * @param exitCode the git process's exit code; authoritative only on {@link Termination#EXITED}
 * @param stdout the process's standard output, captured in full on a normal exit
 * @param stderr the process's standard error, captured in full on a normal exit
 * @param termination how the invocation ended
 */
record GitCommandResult(int exitCode, String stdout, String stderr, Termination termination) {

    /** A result for a command that ran to its own exit — the shape every caller had before FR6. */
    GitCommandResult(int exitCode, String stdout, String stderr) {
        this(exitCode, stdout, stderr, Termination.EXITED);
    }

    /**
     * The git evidence a cannot-verify outcome carries: how this result ended, and what it said.
     * Shared by {@link RoundBoundaryCheck} and {@link HarvestedBoundaryCheck}, whose boundary
     * diffs both classify a non-zero or non-exiting invocation as cannot-verify.
     */
    String cannotVerifyDetail() {
        return "the boundary could not be verified (git " + termination() + ", exit " + exitCode() + "): "
                + stderr().trim();
    }
}
