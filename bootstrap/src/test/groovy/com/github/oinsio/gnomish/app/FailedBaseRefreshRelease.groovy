package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path

/**
 * The two halves of a fresh claim whose base refresh met an unreachable {@code origin} (D9, NFR-R1,
 * NFR-R3 of add-base-ref-resolution), exposed to the kill-point table — which lives in a sibling
 * package and so cannot name the package-private {@link FreshClaimBaseBinding} itself.
 *
 * <p>Both methods drive the production components, not stand-ins: the real {@link BaseRefGit} read
 * and the real resolve-then-refresh step whose one answer to an unanswered remote is the plain
 * claim release.
 */
class FailedBaseRefreshRelease {

    private FailedBaseRefreshRelease() {}

    /**
     * Runs the narrow base refresh alone — the read that lands nothing durable, whichever way it
     * answers.
     *
     * @return true when the unreachable origin was classified as an infrastructure condition
     *     ({@code Unavailable}), never as a refusal about the repository
     */
    static boolean refreshIsUnavailable(BaseRefGit baseRefGit, Path cloneDir, String base) {
        baseRefGit.refresh(cloneDir, base) instanceof BaseRefreshOutcome.Unavailable
    }

    /**
     * Runs the production fresh-claim base binding over the same unreachable origin, which
     * classifies the failure by cause and releases the claim (D9).
     *
     * @return true when the claim was released with the typed infrastructure result — never a park,
     *     and never a burned attempt
     */
    static boolean releasesClaim(BaseRefGit baseRefGit, Path cloneDir, TrackerTask task, Tracker tracker) {
        def request = new FreshClaimBaseBinding.Request(
                null, task, new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch('base')))
        def outcome = FreshClaimBaseBinding.bind(
                baseRefGit, cloneDir, request, TaskState.atStageStart('build'), tracker)
        outcome instanceof FreshClaimBaseBinding.Released &&
                (outcome as FreshClaimBaseBinding.Released).result() instanceof TakeResult.InfrastructureUnavailable
    }
}
