package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.logtext.LogText;
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
     * Why an invocation did not deliver what its caller asked for, phrased for an operator report:
     * the termination first, the exit code and git's own words only when the command actually ran
     * to its own exit. {@code what} names the invocation in the caller's vocabulary ("fetch",
     * "refs read"), so one sentence shape serves every network call site.
     *
     * <p>Extracted when the base refresh became the third caller of what {@link TaskBranchLocator}
     * and {@link RemoteDefaultBranch} both needed (rule of three, {@code manual-sync-pairs.md}).
     * git's stderr is subprocess output that reaches logs, {@code task.json}, and escalation
     * reports, so it is scrubbed and sanitized here, where it enters the factory's own text.
     *
     * <p>Implements FR5, FR9 of add-base-ref-resolution.
     */
    String failureDetail(String what) {
        return switch (termination()) {
            case TIMED_OUT -> "the " + what + " timed out";
            case INTERRUPTED -> "the " + what + " was interrupted";
            case EXITED ->
                "the " + what + " exited " + exitCode() + ": " + LogText.forLog(CredentialScrub.scrub(stderr().trim()));
        };
    }

    /**
     * The git evidence a cannot-verify outcome carries: how this result ended, and what it said.
     * Shared by {@link RoundBoundaryCheck} and {@link HarvestedBoundaryCheck}, whose boundary
     * diffs both classify a non-zero or non-exiting invocation as cannot-verify.
     *
     * <p>git's stderr is subprocess output, and this string travels into a {@code
     * GitPersistFailedException} message that is rendered into a log record, into {@code task.json}
     * and into the escalation report. The log-call gate cannot see inside an exception's message,
     * so the sanitizing happens here, where the untrusted text enters it (FR6 of
     * harden-logging-observability; {@code .claude/rules/logging.md}).
     */
    String cannotVerifyDetail() {
        return "the boundary could not be verified (git " + termination() + ", exit " + exitCode() + "): "
                + LogText.forLog(stderr().trim());
    }
}
