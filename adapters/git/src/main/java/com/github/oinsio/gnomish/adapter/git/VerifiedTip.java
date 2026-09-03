package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.util.Optional;

/**
 * The one reading of a {@code rev-parse} result that is allowed to become a tip (FR13 of
 * harden-logging-observability). A tip resolution answers with a SHA on stdout and nothing else,
 * so an unverified {@code .stdout().trim()} turns every failure — a damaged repository, a ref that
 * does not exist, a read killed on a deadline — into the empty string, which then travels on as if
 * it were a commit: recorded into an attempt record that outlives the process, or compared against
 * the last observed tip and reported as movement.
 *
 * <p>Two readings, one rule. {@link #read} is for probes that may skip an observation ({@link
 * MidRoundHarvestListener}): a failed resolution is {@link Optional#empty()}, never a tip and never
 * a change. {@link #required} is for every resolution whose result is recorded durably or gates a
 * decision ({@link EnvironmentAttemptPersistence}, {@link EnvironmentRoundSnapshot}, {@link
 * GitAttemptPersistence}'s round baseline): a failed resolution throws with the git evidence, so the
 * operation fails instead of writing or comparing a blank value.
 *
 * <p>A single implementation rather than a rule repeated per medium — the host and sandboxed
 * attempt-persistence twins both resolve tips, and this is the seam that keeps them from drifting
 * (`.claude/rules/manual-sync-pairs.md`, preference 1: shared abstraction over declared pair).
 *
 * <p>Implements FR13 of harden-logging-observability.
 */
final class VerifiedTip {

    private VerifiedTip() {}

    /**
     * The tip a resolution established, or empty when it established nothing.
     *
     * @param result the {@code rev-parse} invocation's outcome
     * @return the trimmed SHA when the command ran to a zero exit and printed one; empty otherwise
     */
    static Optional<String> read(GitCommandResult result) {
        if (result.termination() != Termination.EXITED || result.exitCode() != 0) {
            return Optional.empty();
        }
        String tip = result.stdout().trim();
        return tip.isEmpty() ? Optional.empty() : Optional.of(tip);
    }

    /**
     * The tip a resolution established, refusing to continue without one.
     *
     * @param revision the ref or revision that was being resolved, for the failure message
     * @param command the git subcommand that resolved it, e.g. {@code "rev-parse"}
     * @param result the invocation's outcome
     * @return the trimmed SHA
     * @throws BranchTipUnavailableException if the invocation did not run to its own exit, exited
     *     non-zero, or printed no ref at all
     */
    static String required(String revision, String command, GitCommandResult result) {
        return read(result).orElseThrow(() -> unavailable(revision, command, result));
    }

    /**
     * The git evidence a skipped observation carries: how the read ended, and what it said. The
     * non-throwing counterpart of {@link #unavailable}'s message, for the read-only polls that log
     * a failed resolution instead of throwing it.
     *
     * @param result the {@code rev-parse} invocation's outcome
     * @return a one-line reason naming the termination, the exit status and git's own stderr
     */
    static String failureReason(GitCommandResult result) {
        return "git rev-parse " + result.termination() + ", exit " + result.exitCode() + ": "
                + result.stderr().trim();
    }

    /**
     * The refusal, carrying git's own words. The stderr is sanitized here rather than at whichever
     * handler logs the exception: the untrusted-text gate reads log call sites, and an exception
     * message escapes it structurally, so the throw site owns the sanitizing (FR6 of
     * harden-logging-observability; {@code .claude/rules/logging.md}).
     */
    private static BranchTipUnavailableException unavailable(String revision, String command, GitCommandResult result) {
        if (result.termination() != Termination.EXITED) {
            return new BranchTipUnavailableException(
                    revision, command, result.termination().name());
        }
        return new BranchTipUnavailableException(
                revision,
                command,
                result.exitCode(),
                LogText.forLog(result.stderr().trim()));
    }
}
