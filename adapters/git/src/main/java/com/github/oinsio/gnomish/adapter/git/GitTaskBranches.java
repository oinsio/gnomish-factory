package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchStateResult;
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.git.TaskListRow;
import java.nio.file.Path;
import java.util.List;

/**
 * The git-subprocess implementation of {@link TaskBranchGit} (FR12b, design D12 of
 * split-into-modules): a thin facade over this package's existing single-purpose collaborators,
 * all sharing one {@link GitProcessRunner} so a slot's repo-level mutating commands serialize
 * against the same clone (design D8 of add-git-workflow — see {@link GitProcessRunner}'s class
 * javadoc for why sharing the runner matters).
 *
 * <p>Holds no logic of its own: every method delegates verbatim to the collaborator that already
 * implemented it, so this class exists purely to give the {@code application} layer one bound
 * capability seam instead of a dozen construction sites.
 *
 * <p>Implements FR2, FR9, FR10, FR13 of add-git-workflow; FR12b of split-into-modules.
 */
public final class GitTaskBranches implements TaskBranchGit {

    private final FactoryCloneHardening hardening;
    private final ContainerResumeBranch resumeBranch;
    private final TaskBranchLocator locator;
    private final TaskBranchLister lister;
    private final BranchStateReader stateReader;
    private final DeliveredBranchReader deliveredReader;
    private final BranchPush push;

    /**
     * @param runner the git subprocess runner shared across this facade's collaborators; never null
     */
    public GitTaskBranches(GitProcessRunner runner) {
        this.hardening = new FactoryCloneHardening(runner);
        this.resumeBranch = new ContainerResumeBranch(runner);
        this.locator = new TaskBranchLocator(runner);
        this.lister = new TaskBranchLister(runner);
        this.stateReader = new BranchStateReader(runner);
        this.deliveredReader = new DeliveredBranchReader(runner);
        this.push = new BranchPush(runner);
    }

    @Override
    public void harden(Path cloneDir) {
        hardening.harden(cloneDir);
    }

    @Override
    public boolean ensureLocalTaskBranch(Path cloneDir, String taskId) {
        return resumeBranch.ensureLocalBranch(cloneDir, taskId);
    }

    @Override
    public BranchLocation locate(Path cloneDir, String taskId) {
        return locator.locate(cloneDir, taskId);
    }

    @Override
    public List<TaskListRow> list(Path cloneDir) {
        return lister.list(cloneDir);
    }

    @Override
    public BranchStateResult readState(Path cloneDir, String taskId) {
        return stateReader.read(cloneDir, taskId);
    }

    @Override
    public DeliveredBranchState readDelivered(Path cloneDir, String taskId) {
        return deliveredReader.read(cloneDir, taskId);
    }

    @Override
    public void pushBestEffort(Path worktreeRoot, String branch) {
        push.pushBestEffort(worktreeRoot, branch);
    }
}
