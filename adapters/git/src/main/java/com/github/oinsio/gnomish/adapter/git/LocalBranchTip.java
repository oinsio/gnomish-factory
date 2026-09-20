package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the commit a task branch points at in the factory clone — the host-side local tip the
 * touchpoint reconciliation and the park fence both compare against what {@code origin} holds
 * (FR3, FR4 of fix-lifecycle-push). Shared by {@link TaskBranchReconciliation} and {@link
 * ParkDeliveryFence} so the read exists once; container mode uses its own bare-object reader
 * instead (design D3).
 *
 * <p>A branch that does not exist locally reads as empty rather than as a failure: there is
 * nothing to deliver, which is a normal answer for a task whose branch lives only on the remote.
 */
// Not a record: this is a behavior-bearing reader over the git seam (a collaborator, not immutable
// data), kept as a plain final class for parity with its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
@UntrustedParser
final class LocalBranchTip {

    private final GitProcessRunner runner;

    LocalBranchTip(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Returns the branch's local tip, or empty when the clone has no such branch.
     *
     * @param repo the clone to read the branch ref from; never null
     * @param branch the task branch name; never blank
     * @return the branch's local tip, or empty when the clone has no such branch
     */
    Optional<String> read(Path repo, String branch) {
        GitCommandResult tip = runner.run(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch);
        // @UntrustedParser warrant (design D11): what leaves here is git's own object id for a
        //     ref this factory named — used only as a revision argument and compared for equality,
        //     never rendered to a reader, so no untrusted text escapes as text.
        return tip.exitCode() == 0 ? Optional.of(tip.stdout().forParsing().trim()) : Optional.empty();
    }
}
