package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path

/**
 * Runs the production fresh-claim base binding for {@code app.serve}'s {@code
 * OutageWarnFanOutSpec}, which cannot name the package-private {@link FreshClaimBaseBinding}
 * itself — the same reason {@code bootstrap}'s {@code FailedBaseRefreshRelease} exists for the
 * kill-point table. That spec counts the console's WARN fan-out, never the returned {@link
 * FreshClaimBaseBinding.Outcome} (its {@code Released} variant is itself package-private), so this
 * bridge only runs the step for its logging side effect.
 */
class FreshClaimBaseBindingLogRun {

    private FreshClaimBaseBindingLogRun() {}

    static void bind(BaseRefGit baseRefGit, Path cloneDir, TrackerTask task, Tracker tracker) {
        def request = new FreshClaimBaseBinding.Request(
                null, task, new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch('main')))
        FreshClaimBaseBinding.bind(baseRefGit, cloneDir, request, TaskState.atStageStart('build'), tracker)
    }
}
