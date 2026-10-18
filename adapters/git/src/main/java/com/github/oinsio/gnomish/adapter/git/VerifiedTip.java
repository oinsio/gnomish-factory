package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import java.util.Optional;
import java.util.regex.Pattern;

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
@UntrustedParser
final class VerifiedTip {

    /**
     * The fixed shape of a full object id: 40 lowercase hex characters (SHA-1) or 64 (SHA-256).
     * Nothing else a resolution prints is a tip.
     */
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private VerifiedTip() {}

    /**
     * The tip a resolution established, or empty when it established nothing.
     *
     * @param result the {@code rev-parse} invocation's outcome
     * @return the trimmed SHA when the command ran to a zero exit and printed exactly one full
     *     object id; empty otherwise
     */
    static Optional<String> read(GitCommandResult result) {
        if (!result.succeeded()) {
            return Optional.empty();
        }
        // @UntrustedParser warrant (design D11): the value this yields passed the fixed shape of a
        //     full object id — 40 or 64 lowercase hex characters, checked over the whole trimmed
        //     answer — so it holds no separator, no control character and no option prefix; it
        //     travels as a revision argument and an equality subject (F105).
        String tip = result.stdout().forParsing().trim();
        return isObjectId(tip) ? Optional.of(tip) : Optional.empty();
    }

    /**
     * Whether {@code value} is exactly one full object id — the shape a resolution must print, and
     * the one {@link RefTransaction.Update} accepts for a commit it writes into its script.
     *
     * @param value the candidate, untrimmed
     * @return true only for 40 or 64 lowercase hex characters and nothing else
     */
    static boolean isObjectId(String value) {
        return OBJECT_ID.matcher(value).matches();
    }

    /**
     * The object id an invocation that exited zero printed, for a reader whose empty answer means
     * "no such ref": empty only when the invocation did not succeed, and a refusal — never empty —
     * when it succeeded and printed anything but one full object id, since a reader that took that
     * for "absent" would recreate or delete a branch on the strength of a prefix (FR17 of
     * fix-terminal-receipt-convergence, design D15 as amended 2026-10-17: the second layer).
     *
     * @param revision the ref or revision that was read, for the refusal
     * @param command the git subcommand that read it, e.g. {@code "rev-parse"}
     * @param result the invocation's outcome
     * @return the trimmed object id, or empty when the invocation did not succeed
     * @throws BranchTipUnavailableException if it succeeded without printing one full object id
     */
    static Optional<String> answered(String revision, String command, GitCommandResult result) {
        return result.succeeded() ? Optional.of(shaped(revision, command, result)) : Optional.empty();
    }

    /**
     * The whole trimmed stdout of {@code result} as one full object id, refused otherwise; the
     * caller has already decided that the invocation's exit makes its stdout an answer.
     *
     * @param revision the ref or revision that was read, for the refusal
     * @param command the git subcommand that read it
     * @param result the invocation's outcome
     * @return the trimmed object id
     * @throws BranchTipUnavailableException if the stdout is anything but one full object id
     */
    static String shaped(String revision, String command, GitCommandResult result) {
        // @UntrustedParser warrant (design D11): what leaves passed the fixed shape of a full object
        //     id, checked over the whole trimmed answer (F105).
        return shaped(revision, command, result.stdout().forParsing().trim(), result);
    }

    /**
     * {@code value}, a field the caller lifted out of {@code result}'s stdout (the first field of
     * an {@code ls-remote} line), as one full object id, refused otherwise.
     *
     * @param revision the ref or revision that was read, for the refusal
     * @param command the git subcommand that read it
     * @param value the candidate field
     * @param result the invocation's outcome, whose exit code and stderr the refusal carries
     * @return {@code value}
     * @throws BranchTipUnavailableException if {@code value} is anything but one full object id
     */
    static String shaped(String revision, String command, String value, GitCommandResult result) {
        if (!isObjectId(value)) {
            throw new BranchTipUnavailableException(
                    revision, command, result.exitCode(), result.stderr().forLog());
        }
        return value;
    }

    /**
     * The tip a resolution established, refusing to continue without one.
     *
     * @param revision the ref or revision that was being resolved, for the failure message
     * @param command the git subcommand that resolved it, e.g. {@code "rev-parse"}
     * @param result the invocation's outcome
     * @return the trimmed SHA
     * @throws BranchTipUnavailableException if the invocation did not run to its own exit, exited
     *     non-zero, or printed anything but one full object id
     */
    static String required(String revision, String command, GitCommandResult result) {
        return read(result).orElseThrow(() -> unavailable(revision, command, result));
    }

    /**
     * The git evidence a skipped observation carries: how the read ended, and what it said. The
     * non-throwing counterpart of {@link #unavailable}'s message, for the read-only polls that log
     * a failed resolution instead of throwing it.
     *
     * <p>Unlike {@link #unavailable}, this one does not sanitize git's stderr, and the asymmetry is
     * the rule rather than a gap: the refusal message escapes the untrusted-text gate structurally,
     * so its throw site must sanitize, while this string is handed to a log call site — {@link
     * MidRoundPollLog#failed}, which routes every reason through {@code LogText} on all four
     * suppression edges (pinned by {@code MidRoundPollLogSpec}). Sanitizing here as well would cap
     * and flatten the evidence twice ({@code .claude/rules/logging.md}, FR6).
     *
     * @param result the {@code rev-parse} invocation's outcome
     * @return a one-line reason naming the termination, the exit status and git's own stderr
     */
    static String failureReason(GitCommandResult result) {
        return "git rev-parse " + result.termination() + ", exit " + result.exitCode() + ": " + result.stderr();
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
                revision, command, result.exitCode(), result.stderr().forLog());
    }
}
