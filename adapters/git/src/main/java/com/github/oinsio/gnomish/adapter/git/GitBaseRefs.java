package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery;
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome;
import java.nio.file.Path;

/**
 * The git realization of {@link BaseRefGit}: default-branch discovery through {@link
 * RemoteDefaultBranch} and the narrow base refresh through {@link BaseRefresh}, both under one
 * bounded infrastructure retry so an outage is retried the same number of times whichever read
 * meets it first (FR9, NFR-R1 of add-base-ref-resolution).
 *
 * <p>A pure composition: the two collaborators own every decision — what counts as a refusal, what
 * as an outage, which refspec a kind fetches under — and this class only carries them into the
 * {@link com.github.oinsio.gnomish.app.port.git.TaskGit} bundle the composition root builds.
 *
 * <p>Implements FR5, FR6, FR9 of add-base-ref-resolution.
 */
public final class GitBaseRefs implements BaseRefGit {

    private final RemoteDefaultBranch defaultBranch;
    private final BaseRefresh refresh;
    private final ResumeBaseResolution resumeResolution;
    private final OriginProbe originProbe;

    /**
     * @param runner the git process runner of the factory clone; never null
     * @param retry the bounded retry both reads share — the production one from the composition
     *     root, a virtual-time one in specs; never null
     */
    public GitBaseRefs(GitProcessRunner runner, GitInfrastructureRetry retry) {
        this.defaultBranch = new RemoteDefaultBranch(runner, retry);
        this.refresh = new BaseRefresh(runner, retry);
        this.resumeResolution = new ResumeBaseResolution(runner, retry);
        this.originProbe = new OriginProbe(runner);
    }

    @Override
    public DefaultBranchDiscovery discoverDefaultBranch(Path cloneDir) {
        return defaultBranch.discover(cloneDir);
    }

    @Override
    public BaseRefreshOutcome refresh(Path cloneDir, String ref) {
        return refresh.refresh(cloneDir, ref);
    }

    @Override
    public ResumeBaseOutcome resolveForResume(Path cloneDir, String ref) {
        return resumeResolution.resolve(cloneDir, ref);
    }

    @Override
    public boolean probe(Path cloneDir) {
        // Deliberately NOT run through the bounded infrastructure retry the other three reads
        // share: the probe IS the outage/recovery signal (task 7.3's remote outage gate), so
        // retrying it here would blur "one failed probe" into "several", double-counting against
        // the gate's own jittered schedule instead of reporting the single answer it asked for.
        return originProbe.answers(cloneDir);
    }
}
