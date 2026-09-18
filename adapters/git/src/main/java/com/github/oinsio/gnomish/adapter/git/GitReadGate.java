package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.subprocess.Termination;

/**
 * The gate every revision-scoped git read in this package passes through: a result is a fact
 * about the revision only when the invocation ran to its own exit. An interrupted read's capture
 * may be a prefix of the real output rather than a clean answer ({@code CaptureRunner} reports a
 * clean exit under a set interrupt flag as {@link Termination#INTERRUPTED}), so a truncated
 * envelope must surface as unavailability instead of being read as an absence, a stale ancestry
 * verdict, or a corrupt value.
 *
 * <p>Extracted once the same private method existed independently in {@link GitShowTip}, {@link
 * UsageHistoryWalker} and {@link ReplicaPairReconciler} — a third copy of one rule (rule of three,
 * {@code manual-sync-pairs.md}).
 */
final class GitReadGate {

    private GitReadGate() {}

    static GitCommandResult answered(String revision, String command, GitCommandResult result) {
        return switch (result.termination()) {
            case EXITED -> result;
            case TIMED_OUT, INTERRUPTED ->
                throw new BranchTipUnavailableException(
                        revision, command, result.termination().name());
        };
    }
}
