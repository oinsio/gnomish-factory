package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;

/**
 * Carries the repository coordinates and attempt commit this adapter needs to poll — {@code
 * owner}, {@code repo}, {@code attemptCommitSha} — behind the opaque {@link Workspace} marker
 * (design D1 of add-stage-engine: the engine never inspects a workspace, only the adapter that
 * owns its shape does), following {@link
 * com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace}'s house style of adapter-specific
 * data behind the marker, downcast internally by the one adapter that understands it.
 *
 * <p><b>Temporary stand-in.</b> No production {@link Workspace} implementation in this codebase
 * carries a git SHA today — that is add-sandbox-core's attempt-commit round protocol (FR21/D15),
 * not yet implemented. Until it lands, {@link GithubCheckExternalClient} downcasts {@link
 * Workspace} to this type to obtain the attempt commit it must poll against (design D2: the
 * attempt SHA is what makes polling stateless and takeover-idempotent, NFR-R2). This is
 * provisional plumbing, not a domain decision: a real integration will likely replace this class
 * with whatever add-sandbox-core's environment/workspace type turns out to be, at which point
 * {@link GithubCheckExternalClient} is expected to downcast to that type instead.
 *
 * <p>Implements NFR-R2 of add-external-check-github-actions. Depends on add-sandbox-core FR21/D15
 * for its eventual replacement.
 */
public final class GithubCheckWorkspace implements Workspace {

    private final String owner;
    private final String repo;
    private final String attemptCommitSha;

    /**
     * @param owner the repository owner the attempt commit was pushed to
     * @param repo the repository name the attempt commit was pushed to
     * @param attemptCommitSha the attempt commit under verification (design D2)
     */
    public GithubCheckWorkspace(String owner, String repo, String attemptCommitSha) {
        this.owner = owner;
        this.repo = repo;
        this.attemptCommitSha = attemptCommitSha;
    }

    /** The repository owner, for scoping the workflow-runs and jobs queries. */
    public String owner() {
        return owner;
    }

    /** The repository name, for scoping the workflow-runs and jobs queries. */
    public String repo() {
        return repo;
    }

    /** The attempt commit SHA to match runs against (FR1, D2). */
    public String attemptCommitSha() {
        return attemptCommitSha;
    }
}
