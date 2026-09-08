package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The resume-time counterpart of {@link BaseRefresh} (FR12, design D13 of add-base-ref-resolution):
 * a clone with {@code origin} configured narrow-fetches the pinned ref exactly as a fresh claim's
 * base refresh does, reusing {@link BaseRefresh} rather than a second fetch implementation; a clone
 * with no {@code origin} at all reads the ref's LOCAL tip instead of refusing — a resume of a
 * remote-less clone is a legitimate shape a fresh claim never has to handle, since {@code take}
 * fails fast at startup without one (D14) but a resume may outlive the reason for that.
 *
 * <p>Implements FR12, D13 of add-base-ref-resolution.
 */
final class ResumeBaseResolution {

    private final GitProcessRunner runner;
    private final OriginRemote origin;
    private final BaseRefresh refresh;

    ResumeBaseResolution(GitProcessRunner runner, GitInfrastructureRetry retry) {
        this.runner = runner;
        this.origin = new OriginRemote(runner);
        this.refresh = new BaseRefresh(runner, retry);
    }

    /**
     * Resolves {@code ref}'s current tip for a resume rebind.
     *
     * @param cloneDir the factory clone; never null
     * @param ref the task's pinned base ref; never null
     * @return the resolved tip, or the typed refusal or outage; never null
     */
    ResumeBaseOutcome resolve(Path cloneDir, String ref) {
        if (!origin.isConfigured(cloneDir)) {
            return localTip(cloneDir, ref)
                    .<ResumeBaseOutcome>map(commit -> new ResumeBaseOutcome.Bound(ref, commit))
                    .orElseGet(() -> new ResumeBaseOutcome.Refused(noRemoteUnresolvedReport(ref)));
        }
        return switch (refresh.refresh(cloneDir, ref)) {
            case BaseRefreshOutcome.Refreshed(String resolved, String commit, var ignoredKind) ->
                new ResumeBaseOutcome.Bound(resolved, commit);
            case BaseRefreshOutcome.Refused(String report) -> new ResumeBaseOutcome.Refused(report);
            case BaseRefreshOutcome.Unavailable(String reason) -> new ResumeBaseOutcome.Unavailable(reason);
        };
    }

    /** The local-only equivalent of the narrow fetch: {@code rev-parse}, no network attempted. */
    private Optional<String> localTip(Path cloneDir, String ref) {
        return VerifiedTip.read(runner.run(cloneDir, "rev-parse", "--verify", "--quiet", ref + "^{commit}"));
    }

    private static String noRemoteUnresolvedReport(String ref) {
        return "The base '" + ref + "' cannot be rebound: this clone has no 'origin' remote to fetch from, and "
                + "'" + ref + "' does not resolve locally either. Fetch the ref into this clone, or correct the "
                + "base this task's pin names.";
    }
}
