package com.github.oinsio.gnomish.app.port.git;

import org.jspecify.annotations.Nullable;

/**
 * The round identity of one git-mode host run (design D22 of migrate-verify-to-executor-contract,
 * "The run's round cell has one owner"): the run's one {@link CurrentRound}, and the interrupted
 * verification the resume found, if any. The run preparation constructs it once, before the two
 * readers of the cell are built — the round source the host-git decoration opens rounds in
 * ({@link HostGitRounds}), and the attempt persistence whose range base it is ({@link
 * TaskStoreGit#attemptPersistence}) — so the snapshot, the persistence and the resume act on one
 * round identity (crash-consistency item 14). Nothing attaches a cell after construction.
 *
 * <p>The pending verification reaches the preparation as an argument from the path that asked the
 * no-salvage owner, never by a second read of the register inside the preparation — the shape of the
 * container's {@code ContainerRunSupport}, the container's per-run owner.
 *
 * <p>Implements FR19 of migrate-verify-to-executor-contract.
 *
 * @param rounds the run's round cell; never null
 * @param pending the interrupted verification a resume continues, or {@code null} on a fresh run and
 *     on a resume that starts from a closed round
 */
public record RunRound(CurrentRound rounds, @Nullable PendingVerification pending) {

    /**
     * A new run's round identity: a fresh cell beside {@code pending}.
     *
     * @param pending the interrupted verification the resume found, or {@code null}
     * @return the run's round identity; never null
     */
    public static RunRound of(@Nullable PendingVerification pending) {
        return new RunRound(new CurrentRound(), pending);
    }
}
