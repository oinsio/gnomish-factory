package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.PushBestEffortTaskLifecycleStore
import com.github.oinsio.gnomish.adapter.git.VirtualTimeGitRetries
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path

/**
 * The lifecycle store of a kill-point world as production assembles it: the strict repository
 * behind the closing push, so every closing commit becomes the factory register (design D13 of
 * tighten-spec-contract) — a kill after a step freezes the register on that step's commit, as it
 * does in production, and the pickup reads the branch at it.
 */
final class RegisteringStore {

    private RegisteringStore() {}

    static TaskLifecycleStore of(TaskLifecycleStore strict, GitProcessRunner runner, Path repo) {
        new PushBestEffortTaskLifecycleStore(strict, runner, repo, VirtualTimeGitRetries.gitInfrastructure(), new VirtualClock())
    }
}
