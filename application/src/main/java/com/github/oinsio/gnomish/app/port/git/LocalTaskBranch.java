package com.github.oinsio.gnomish.app.port.git;

/**
 * A task branch that exists as a local branch in the clone it was answered for — the view of a
 * {@link BranchAdoption} a writer's entry accepts (design D10 of fix-terminal-receipt-convergence).
 * It is obtained from the adoption owner only — {@link TaskBranchGit#ensureLocalTaskBranch}, or
 * {@link TaskBranchGit#adopt} over a location the caller just looked up: either the head was
 * {@linkplain BranchAdoption.Adopted adopted} by that call, or it was {@linkplain
 * BranchAdoption.AlreadyLocal already local}. The records' constructors are public, as a record's
 * must be; {@code TaskBranchCommandGateSpec} keeps their production construction to the owner.
 *
 * <p>Implements FR7, FR8 of fix-terminal-receipt-convergence.
 */
public sealed interface LocalTaskBranch extends BranchAdoption
        permits BranchAdoption.Adopted, BranchAdoption.AlreadyLocal {

    /**
     * The task whose branch this is.
     *
     * @return the tracker's original taskId; never null
     */
    String taskId();
}
