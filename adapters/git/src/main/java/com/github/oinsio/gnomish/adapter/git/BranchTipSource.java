package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.TipRelation;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Optional;

/**
 * The narrow media seam the branch-shape classification reads through (design D3 of
 * harden-task-branch-contract): "read this file at the registered commit", whether that commit is
 * the cleanup commit, and where the branch tip stands relative to it (design D13 of
 * tighten-spec-contract). {@link RefTipSource} is the production implementation, over a located ref
 * of a clone — a local branch or a remote-tracking ref — and the clone's register of the same task.
 *
 * <p>The seam stays an interface rather than collapsing into that class: it is what keeps {@link
 * BranchTipFactsReader} — and through it the domain classifier — independent of git, and what lets
 * a spec hand the reader a branch built in memory instead of a repository.
 *
 * <p>Every implementation reads the <em>registered commit</em>, never the tip and never the working
 * copy: the tip may carry what the gnome wrote above the register, and a dirty worktree's {@code
 * .gnomish-task/} files are precisely what a crashed instance may have left half-written (FR5 of
 * harden-task-branch-contract).
 *
 * <p>Implements FR1, FR5, FR13 of harden-task-branch-contract; FR13, NFR-S2 of
 * tighten-spec-contract.
 */
public interface BranchTipSource {

    /**
     * Reads one file as it stands at the registered commit.
     *
     * @param path the repository-relative path, e.g. {@code .gnomish-task/task.json}
     * @return the file's text, or empty when there is no registered commit or it does not carry
     *     the file
     */
    Optional<UntrustedText> readAtRegister(String path);

    /**
     * Whether the registered commit is the cleanup commit — the delivery fact.
     *
     * @return {@code true} when the task was delivered
     */
    boolean registeredCleanup();

    /**
     * Where the branch tip stands relative to the registered commit.
     *
     * @return the relation; never null
     */
    TipRelation tipRelation();
}
