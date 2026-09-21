package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.TextSafety;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The outcome of one {@code git} subprocess invocation: how the invocation ended, its exit code,
 * and stdout/stderr captured as separate streams (unlike {@code CommandProcessRunner}'s
 * merged-stream approach for shell checks) so callers can parse git plumbing output cleanly while
 * still seeing warnings on stderr.
 *
 * <p>Both streams are {@link UntrustedText}: git speaks for remotes, for image authors and for
 * whatever another instance wrote on a branch, so its output is attacker-influenced whichever
 * stream it arrives on (design D3, D11 of type-untrusted-text). Stderr reaches logs, reports and
 * {@code task.json} through an exit; stdout is read by the family's parsers through
 * {@code forParsing()}, which hands over the bytes as git wrote them — a capped, flattened
 * rendering would break every parse.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.sandbox.environment.DockerResult}: both
 * mint their subprocess family's two streams as {@link UntrustedText} through the same
 * three/four-argument {@code of} factory shape, defaulting to {@link Termination#EXITED}. They
 * stay separate types because git additionally credential-scrubs stderr; only that factory shape
 * and the untrusted-text minting must stay aligned.
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
 * its own exit is {@link Termination#EXITED}, which the three-argument factory supplies — the
 * construction sites and specs that predate the bound are unchanged and stay correct (NFR-R3).
 *
 * <p>Implements FR2 of add-git-workflow; FR6, NFR-R3 of bound-subprocess-commands; FR1, FR7 of
 * type-untrusted-text.
 *
 * @param exitCode the git process's exit code; authoritative only on {@link Termination#EXITED}
 * @param stdout the process's standard output, captured in full on a normal exit
 * @param stderr the process's standard error, captured in full on a normal exit, credential-scrubbed
 * @param termination how the invocation ended
 */
record GitCommandResult(int exitCode, UntrustedText stdout, UntrustedText stderr, Termination termination) {

    /**
     * How many characters of git's stderr one detail below quotes — characters of the
     * <em>rendered</em> excerpt, since {@link UntrustedText#excerpt(int)} bounds what leaves it
     * (FR10 of fix-envelope-medium). Deliberately well under {@link TextSafety#DEFAULT_CAP_CHARS},
     * and that is the whole point: every caller quotes these
     * sentences inside prose of its own — {@link CommitBaseFetch} and {@link RefreshedTip} add the
     * refusal's explanation, {@link TaskBranchLocator} names what origin said, {@link
     * GitPersistFailedException} names the round it failed to persist — and the log exit's cap
     * keeps the <em>tail</em>. A detail sized to the log cap therefore fills it by itself, and the
     * exit then drops exactly the head: the caller's prose and this sentence's own "the fetch
     * exited 128" opening, leaving a record that is git's words and nothing naming what failed.
     * The headroom between this bound and the log cap is what that prose, and the truncation
     * marker the cap writes, fit into.
     *
     * <p>Because the bound is on the rendered text, that headroom holds for <b>any</b> stderr
     * content. It did not while the bound counted input characters: a capture of nothing but
     * {@code U+2028} renders six characters per one, so 1 400 characters of it left the excerpt
     * as 8 400 and took the whole log cap, head first.
     */
    private static final int STDERR_CAP_CHARS = 1_400;

    /**
     * Captures one invocation's streams: the single place git's text becomes untrusted text, and
     * the single place stderr is scrubbed of remote-URL credentials (NFR-S2 of fix-lifecycle-push).
     * Both live here rather than at {@link GitProcessRunner}'s capture so a result built anywhere
     * else — a spec, a second runner — cannot hold a token or an unminted string; the escape hatch
     * the two details used to guard against by scrubbing again is closed by construction instead.
     *
     * <p>Stdout is deliberately not scrubbed: {@code remote get-url origin} answers through it and
     * {@link OriginRemote}'s caller needs the real URL.
     *
     * @param exitCode the git process's exit code
     * @param stdout the captured standard output
     * @param stderr the captured standard error, scrubbed here
     * @param termination how the invocation ended
     * @return the result, with both streams minted as subprocess output
     */
    static GitCommandResult of(int exitCode, String stdout, String stderr, Termination termination) {
        return new GitCommandResult(
                exitCode,
                UntrustedText.subprocess(stdout),
                UntrustedText.subprocess(CredentialScrub.scrub(stderr)),
                termination);
    }

    /** A result for a command that ran to its own exit — the shape every caller had before FR6. */
    static GitCommandResult of(int exitCode, String stdout, String stderr) {
        return of(exitCode, stdout, stderr, Termination.EXITED);
    }

    /**
     * Why an invocation did not deliver what its caller asked for, phrased for an operator report:
     * the termination first, the exit code and git's own words only when the command actually ran
     * to its own exit. {@code what} names the invocation in the caller's vocabulary ("fetch",
     * "refs read"), so one sentence shape serves every network call site.
     *
     * <p>Extracted when the base refresh became the third caller of what {@link TaskBranchLocator}
     * and {@link RemoteDefaultBranch} both needed (rule of three, {@code manual-sync-pairs.md}).
     * git's stderr is subprocess output that reaches logs, {@code task.json} and escalation
     * reports; it leaves the carrier here through the log exit, which {@link UntrustedText}'s own
     * {@code toString()} is.
     *
     * <p>Implements FR5, FR9 of add-base-ref-resolution.
     */
    UntrustedText failureDetail(String what) {
        return UntrustedText.factory(
                switch (termination()) {
                    case TIMED_OUT -> "the " + what + " timed out";
                    case INTERRUPTED -> "the " + what + " was interrupted";
                    case EXITED -> "the " + what + " exited " + exitCode() + ": " + stderr().excerpt(STDERR_CAP_CHARS);
                });
    }

    /**
     * The git evidence a cannot-verify outcome carries: how this result ended, and what it said.
     * Shared by {@link RoundBoundaryCheck} and {@link HarvestedBoundaryCheck}, whose boundary
     * diffs both classify a non-zero or non-exiting invocation as cannot-verify.
     *
     * <p>git's stderr is subprocess output, and this string travels into a {@code
     * GitPersistFailedException} message that is rendered into a log record, into {@code task.json}
     * and into the escalation report. The log-call gate cannot see inside an exception's message,
     * so the rendering happens here, where the untrusted text enters it (FR6 of
     * harden-logging-observability; {@code .claude/rules/logging.md}).
     */
    UntrustedText cannotVerifyDetail() {
        return UntrustedText.factory("the boundary could not be verified (git " + termination() + ", exit " + exitCode()
                + "): " + stderr().excerpt(STDERR_CAP_CHARS));
    }
}
