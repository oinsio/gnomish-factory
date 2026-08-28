package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.git.BranchStateResult;
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState;
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.git.TaskListRow;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.branch.BranchShapeClassifier;
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
 * <p>Implements FR2, FR9, FR10, FR13 of add-git-workflow; FR12b of split-into-modules; FR1,
 * FR2 of harden-task-branch-contract.
 */
public final class GitTaskBranches implements TaskBranchGit {

    private final GitProcessRunner runner;
    private final FactoryCloneHardening hardening;
    private final ContainerResumeBranch resumeBranch;
    private final TaskBranchLocator locator;
    private final TaskBranchLister lister;
    private final BranchStateReader stateReader;
    private final DeliveredBranchReader deliveredReader;
    private final BranchPush push;
    private final TaskBranchReconciliation reconciliation;
    private final ParkDeliveryFence parkFence;
    private final BranchTipFactsReader facts;
    private final BranchShapeClassifier classifier;
    private final ClaimEpochSource epochs;

    /** The claimless facade — {@code status}, {@code usage} and specs, which hold no tenure. */
    public GitTaskBranches(GitProcessRunner runner) {
        this(runner, ClaimEpochSource.NONE);
    }

    /**
     * @param runner the git subprocess runner shared across this facade's collaborators; never null
     * @param epochs the tenure a shape classification is fenced against (FR13); {@link
     *     ClaimEpochSource#NONE} where no claim is held
     */
    public GitTaskBranches(GitProcessRunner runner, ClaimEpochSource epochs) {
        this.hardening = new FactoryCloneHardening(runner);
        this.resumeBranch = new ContainerResumeBranch(runner);
        this.locator = new TaskBranchLocator(runner);
        this.lister = new TaskBranchLister(runner);
        this.stateReader = new BranchStateReader(runner);
        this.deliveredReader = new DeliveredBranchReader(runner);
        this.push = new BranchPush(runner);
        this.reconciliation = new TaskBranchReconciliation(runner);
        this.parkFence = new ParkDeliveryFence(runner);
        this.facts = new BranchTipFactsReader();
        this.classifier = new BranchShapeClassifier();
        this.epochs = epochs;
        this.runner = runner;
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
    public BranchShape classifyShape(Path cloneDir, String taskId) {
        // The located ref is the medium: no worktree is created, and a branch that lives only on
        // origin classifies from its remote-tracking ref exactly as a local one does (design D3).
        return switch (locator.locate(cloneDir, taskId)) {
            case BranchLocation.Local(String ref) -> shapeAt(cloneDir, taskId, ref);
            case BranchLocation.RemoteTracking(String ref) -> shapeAt(cloneDir, taskId, ref);
            case BranchLocation.NotFound() -> new BranchShape.Bare();
            case BranchLocation.Unavailable(String reason) ->
                throw new BranchLocationUnavailableException(taskId, reason);
        };
    }

    private BranchShape shapeAt(Path cloneDir, String taskId, String ref) {
        return classifier.classify(facts.read(
                new RefTipSource(runner, cloneDir, ref), epochs.epochFor(taskId).orElse(null)));
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

    @Override
    public void reconcileRemote(Path cloneDir, String taskId, String touchpoint) {
        reconciliation.reconcile(cloneDir, taskId, touchpoint);
    }

    @Override
    public ParkDeliveryVerdict fenceParkDelivery(Path cloneDir, String taskId) {
        return parkFence.ensureDelivered(cloneDir, taskId);
    }
}
