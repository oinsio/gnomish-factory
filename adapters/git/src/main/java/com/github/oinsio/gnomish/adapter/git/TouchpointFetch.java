package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.gittransfer.GitTransfer;
import com.github.oinsio.gnomish.gittransfer.Refspec;
import com.github.oinsio.gnomish.gittransfer.TransferSource;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The touchpoint fetch of a task (design D13 of tighten-spec-contract): the task branch and the
 * task's factory register from {@code origin} in one transfer of the owner ({@link
 * GitTransfer#fetch}), so both reflect one advertisement of origin and a closing push another
 * instance made lands here whole. Shared by the two touchpoints that fetch a task branch — the
 * locate of a take, resume or status read ({@link TaskBranchLocator}) and the replica reconcile
 * ({@link ReplicaPairReconciler}); each names where the register lands ({@link
 * FactoryRegisterRefs#fetchedInPlace}, {@link FactoryRegisterRefs#fetchedTracking}).
 *
 * <p>One fallback, and only one: when origin carries no register for the task, git refuses the
 * whole fetch ("couldn't find remote ref", read under the runner's pinned {@code C} locale), so the
 * branch is fetched alone. That is a branch no factory closing push ever reached — created before
 * the register existed, or written by something other than the factory — and the clone then holds
 * no register for it: the reader classifies such a branch {@code Unregistered} rather than guessing
 * (git-task-persistence, "The touchpoint fetch carries the register"). Any other failure is the
 * caller's to classify, exactly as before, so an unreachable origin still costs one fetch.
 *
 * <p>The touchpoint fetch is the first write every task touchpoint makes to the clone — before
 * any run path hardens it, and on the {@code status}/{@code usage} reads that never do — so the
 * clone's ref backend is required here, before the fetch ({@link FactoryCloneRefFormat}): a clone
 * the factory refuses receives no ref (design D13 of tighten-spec-contract, as amended
 * 2026-10-13).
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 */
// Not a record: a behavior-bearing command seam over the git runner, like its siblings.
@SuppressWarnings("ClassCanBeRecord")
final class TouchpointFetch {

    private static final Logger log = LoggerFactory.getLogger(TouchpointFetch.class);

    /** git's refusal of a fetch whose source ref origin does not advertise. */
    private static final String MISSING_REMOTE_REF = "couldn't find remote ref ";

    private final GitRunner runner;
    private final FactoryCloneRefFormat refFormat;

    TouchpointFetch(GitRunner runner) {
        this.runner = runner;
        this.refFormat = new FactoryCloneRefFormat(runner);
    }

    /**
     * Fetches {@code branch} and {@code register} from {@code origin} in one transfer, or {@code
     * branch} alone when origin carries no register for {@code taskId}.
     *
     * @param repo the clone, or a worktree of it
     * @param taskId the tracker's original taskId, naming the register
     * @param branch the branch refspec
     * @param register the register refspec
     * @return the result of the last fetch that ran
     * @throws com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException when the clone
     *     is not on the factory's ref backend; nothing was fetched
     */
    GitCommandResult fetch(Path repo, String taskId, Refspec branch, Refspec register) {
        refFormat.require(repo);
        GitCommandResult both = runner.run(repo, GitTransfer.fetch(TransferSource.ORIGIN, branch, register));
        if (!both.stderr()
                .contains(MISSING_REMOTE_REF + FactoryRegisterRefs.of(taskId).name())) {
            return both;
        }
        // throwable-not-subject: git reported a status; nothing was thrown.
        log.debug("origin carries no factory register for taskId={}, fetching the branch alone", taskId);
        return runner.run(repo, GitTransfer.fetch(TransferSource.ORIGIN, branch));
    }
}
