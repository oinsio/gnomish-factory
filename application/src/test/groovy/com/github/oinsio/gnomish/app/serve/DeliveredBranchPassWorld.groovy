package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.ClaimlessDeliveredCleanup
import com.github.oinsio.gnomish.app.ContainerTakeSupportFixture
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.TrackerTaskFixtures
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskListRow
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.BranchShape
import java.nio.file.Path
import spock.lang.Specification

/**
 * The shared world of the branch-pass specs: the pass over a mocked task-branch port and tracker,
 * with the real claimless owner and the real cleanup behind it on the host medium (FR4, FR11 of
 * fix-terminal-receipt-convergence).
 */
abstract class DeliveredBranchPassWorld extends Specification {

    protected static final Path CLONE_DIR = Path.of('/clones/widgets')
    protected static final def CLONE = RegisteredCloneFixture.unregistered(Path.of('/srv/gnomish'), CLONE_DIR)

    TaskBranchGit branches = Mock(TaskBranchGit)
    Tracker tracker = Mock(Tracker)
    TaskLifecycleStore store = Mock(TaskLifecycleStore)
    TaskEnvironmentDisposal disposal = Mock(TaskEnvironmentDisposal)

    DaemonConditions conditions = DaemonConditionsFixture.closed()

    DeliveredBranchPass pass = new DeliveredBranchPass(CLONE_DIR, branches,
    new ClaimlessDeliveredCleanup(new TaskGit(Stub(TaskStoreGit) {
        taskRepository(CLONE) >> store
    }, branches, Stub(TaskWorktreeGit) {
        environmentDisposal(CLONE) >> disposal
    }, new ClaimEpochBook()), CLONE, ContainerTakeSupportFixture.hostOnly(), [], tracker),
    TrackerTaskFixtures.oneStagePipeline(), conditions)

    protected static TaskListRow row(String taskId, BranchShape shape) {
        new TaskListRow(taskId, null, 0, null, shape)
    }

    protected static def finished(String taskId) {
        TrackerTaskFixtures.taskWith(new TaskRef(taskId), new TrackerTaskState.Finished(), true)
    }

    protected static BranchLocation local(String taskId) {
        new BranchLocation.Local("refs/heads/gnomish/${taskId}")
    }

    protected static BranchLocation tracking(String taskId) {
        new BranchLocation.RemoteTracking("refs/remotes/origin/gnomish/${taskId}")
    }
}
