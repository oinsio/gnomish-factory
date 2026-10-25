package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.git.BranchAdoption;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.git.BranchStateResult;
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState;
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.git.TaskListRow;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@link TaskBranchGit} a serve slot is handed: the real one, with every task-branch lookup
 * that could not reach {@code origin} reported to the remote {@link DaemonCondition} at the moment it
 * returns (FR14 of add-base-ref-resolution; finding F88 of tighten-spec-contract).
 *
 * <p><b>Why the lookup signals too.</b> A fresh claim's first read of {@code origin} is the
 * task-branch lookup ({@link #classifyShape}), not the base refresh: on a dead remote the lookup
 * fails first and the slot releases its claim before any base read is reached. Were only the
 * base-ref port decorated ({@link RemoteOutageSignalingBaseRefGit}), the gate would never open and
 * the feed would claim and release a task every poll for the whole outage — the traffic the gate
 * exists to stop. Both decorations are applied by {@link RemoteOutageGates#signaling}, the one
 * owner of the slot's gate signals.
 *
 * <p><b>Which outcomes signal.</b> Only "origin could not be asked": a {@link
 * BranchLocation.Unavailable} from {@link #locate}, and a {@link
 * BranchLocationUnavailableException} — the same fact, thrown — from the lookups built on it. The
 * exception is rethrown unchanged, so the slot's release path sees exactly what it saw before.
 * Nothing here confirms a refresh: a lookup's result does not say whether origin was contacted (a
 * branch found locally is answered from the clone), and reading a success as a contact would spend
 * the post-close interval reset only a contacted base refresh may spend (design D3 of
 * signal-outage-gate-on-origin-contact). Every other operation passes through untouched.
 *
 * <p><b>Locking.</b> The gate is signalled after the delegate's read has returned; nothing is held
 * across the read ({@code lock-scope.md}).
 *
 * <p>Implements FR14 of add-base-ref-resolution.
 *
 * @param delegate the real task-branch capability every call is forwarded to; never null
 * @param gate the gate the slot's lookups report to — the SAME instance the feed consults; never
 *     null
 */
record RemoteOutageSignalingTaskBranchGit(TaskBranchGit delegate, DaemonCondition gate) implements TaskBranchGit {

    @Override
    public void requireRefFormat(Path cloneDir) {
        delegate.requireRefFormat(cloneDir);
    }

    @Override
    public BranchAdoption ensureLocalTaskBranch(Path cloneDir, String taskId) {
        return signalingOutage(() -> delegate.ensureLocalTaskBranch(cloneDir, taskId));
    }

    /**
     * Passes through: the adoption asks no remote of its own — the caller's {@link #locate}, which
     * already signalled an outage it met, is the only lookup behind the location it is handed.
     */
    @Override
    public BranchAdoption adopt(Path cloneDir, String taskId, BranchLocation location) {
        return delegate.adopt(cloneDir, taskId, location);
    }

    @Override
    public BranchLocation locate(Path cloneDir, String taskId) {
        BranchLocation location = delegate.locate(cloneDir, taskId);
        if (location instanceof BranchLocation.Unavailable(var reason)) {
            gate.openOnFailure(reason.forLog());
        }
        return location;
    }

    @Override
    public BranchShape classifyShape(Path cloneDir, String taskId) {
        return signalingOutage(() -> delegate.classifyShape(cloneDir, taskId));
    }

    /**
     * Wrapped like the other lookups: the read over a caller's location throws the outage only for
     * an {@link BranchLocation.Unavailable} the caller's {@link #locate} already signalled, and a
     * second signal while the gate is open is the gate's own no-op.
     */
    @Override
    public BranchShape classifyShape(Path cloneDir, String taskId, BranchLocation location) {
        return signalingOutage(() -> delegate.classifyShape(cloneDir, taskId, location));
    }

    @Override
    public List<TaskListRow> list(Path cloneDir) {
        return delegate.list(cloneDir);
    }

    @Override
    public BranchStateResult readState(Path cloneDir, String taskId) {
        return signalingOutage(() -> delegate.readState(cloneDir, taskId));
    }

    @Override
    public DeliveredBranchState readDelivered(Path cloneDir, String taskId) {
        return signalingOutage(() -> delegate.readDelivered(cloneDir, taskId));
    }

    @Override
    public void pushClosingBestEffort(Path worktreeRoot, String taskId) {
        delegate.pushClosingBestEffort(worktreeRoot, taskId);
    }

    @Override
    public void reconcileRemote(Path cloneDir, String taskId, String touchpoint) {
        delegate.reconcileRemote(cloneDir, taskId, touchpoint);
    }

    @Override
    public ParkDeliveryVerdict fenceParkDelivery(Path cloneDir, String taskId) {
        return delegate.fenceParkDelivery(cloneDir, taskId);
    }

    /** Runs one lookup; an outage it throws opens the gate and is rethrown unchanged. */
    private <T> T signalingOutage(Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (BranchLocationUnavailableException outage) {
            gate.openOnFailure(outage.reason().forLog());
            throw outage;
        }
    }
}
