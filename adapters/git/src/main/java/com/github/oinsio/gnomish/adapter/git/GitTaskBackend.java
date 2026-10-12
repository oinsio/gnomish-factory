package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import java.time.InstantSource;
import java.util.function.UnaryOperator;

/**
 * The one public way to build the git-backed {@link TaskGit} bundle (design D12, finding F74 of
 * tighten-spec-contract): the three writers — {@link GitTaskStore}, {@link GitTaskBranches}, {@link
 * GitTaskWorktrees} — are built here over the very {@link ClaimEpochBook} the returned bundle
 * carries as its tenure record. Their constructors are package-private, so an assembly whose
 * writers stamp from one book while the tracker (wrapped with the bundle's book) fills another
 * does not compile outside this package (FR4 of fix-claim-epoch-fence;
 * {@code SplitTenureRecordSpec}).
 *
 * <p>An assembly object: it holds no decision, only the construction of the bundle from its
 * arguments, and is exercised end to end through the composition root ({@code
 * ManualRunConfiguration}) and {@code TaskGitFixture}, which the lifecycle specs build their git
 * layer from; the identity of the stamping and the recording book is asserted on a real origin by
 * {@code ServeClaimEpochStampSpec} and the two-instance take lifecycle specs ({@code
 * InMemoryTakeLifecycleCrashReapReclaimSpec}, {@code InMemoryTakeLifecycleEscalateResumeSpec}).
 *
 * <p>It is the one class of an adapter package that names the application-side {@link
 * ClaimEpochBook}: it keeps no record of its own (no field), and only hands the caller's book to the
 * bundle and, as their {@code ClaimEpochSource}, to the writers ({@code LayerReachSpec} pins both).
 *
 * <p>Implements FR13 of tighten-spec-contract.
 */
public final class GitTaskBackend {

    private GitTaskBackend() {}

    /**
     * Builds the git-backed task-git bundle over one tenure record.
     *
     * @param runner the git runner every collaborator shares — the composition root's one {@code
     *     GitProcessRunner}, which serializes repo-level mutating commands per clone; typed as the
     *     role, like every other holder (design D27 of supervise-daemon-loops-and-embed-dashboard);
     *     never null
     * @param retry the infrastructure budget the store, the branch operations and the base-ref
     *     reads are re-attempted under; never null
     * @param clock the time source the store stamps from; never null
     * @param midRoundPush the executor-rounds decoration the bundle carries (FR1, design D3 of
     *     wire-host-mid-round-push); {@link UnaryOperator#identity()} where none is wanted; never null
     * @param epochs the process's one tenure record: the book the three writers stamp from and the
     *     one the bundle carries for the claiming commands to wrap their tracker with; never null
     * @return the bundle; never null
     */
    public static TaskGit taskGit(
            GitRunner runner,
            GitInfrastructureRetry retry,
            InstantSource clock,
            UnaryOperator<RoundEnvironmentSource> midRoundPush,
            ClaimEpochBook epochs) {
        return new TaskGit(
                new GitTaskStore(runner, epochs, retry, clock),
                new GitTaskBranches(runner, epochs, retry),
                new GitTaskWorktrees(runner, epochs),
                midRoundPush,
                new GitBaseRefs(runner, retry),
                epochs);
    }
}
