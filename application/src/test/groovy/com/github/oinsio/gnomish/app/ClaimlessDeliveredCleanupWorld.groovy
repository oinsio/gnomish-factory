package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import java.nio.file.Path
import spock.lang.Specification

/**
 * The shared world of the claimless-cleanup specs: the real owner over a mocked task-branch port and
 * tracker, with the host medium's store and disposal and a container-mode seam whose factory records
 * what it was asked for (FR4, FR11 of fix-terminal-receipt-convergence).
 */
abstract class ClaimlessDeliveredCleanupWorld extends Specification implements RunChainFakes, SandboxBindingFixtures {

    protected static final TaskRef TASK_REF = new TaskRef('PROJ 1')

    TaskBranchGit branches = Mock(TaskBranchGit)
    Tracker tracker = Mock(Tracker)
    TaskLifecycleStore hostStore = Mock(TaskLifecycleStore)
    TaskEnvironmentDisposal worktreeDisposal = Mock(TaskEnvironmentDisposal)
    TaskLifecycleStore boxStore = Mock(TaskLifecycleStore)
    SandboxRunSupport support = Mock(SandboxRunSupport) {
        taskRepository() >> boxStore
    }
    List<List> supportsBuilt = []

    TaskGit git = new TaskGit(Stub(TaskStoreGit) {
        taskRepository(CLONE) >> hostStore
    }, branches, Stub(TaskWorktreeGit) {
        environmentDisposal(CLONE) >> worktreeDisposal
    }, new ClaimEpochBook())

    protected static final BranchLocation LOCAL = new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
    protected static final BranchLocation TRACKING = new BranchLocation.RemoteTracking('refs/remotes/origin/gnomish/PROJ-1')

    protected static def finished() {
        TrackerTaskFixtures.taskWith(TASK_REF, new TrackerTaskState.Finished(), true)
    }

    protected ClaimlessDeliveredCleanup hostCleanup() {
        new ClaimlessDeliveredCleanup(git, CLONE, ContainerTakeSupportFixture.hostOnly(), [], tracker)
    }

    /** The slot's cleanup over a container-mode seam whose factory records what it was asked for. */
    protected ClaimlessDeliveredCleanup containerCleanup() {
        def selector = new SandboxModeSelector(
                new BindingProperties(null, [:]),
                new SandboxProperties('an-image', null, null, null, [], [], false, null, null, null, null),
                AdapterBindingRegistry.ratified([containerProvider()], BindingTrustTable.firstParty()),
                { -> true } as ContainerRuntimeProbe)
        def containers = new ContainerTakeSupport(selector, { Path cloneDir, String taskId, List<Segment> segments, definition, List<String> creds ->
            supportsBuilt << [
                cloneDir,
                taskId,
                segments.size(),
                definition,
                creds
            ]
            support
        } as ContainerSupportFactory)
        def wiring = slotWiring(Stub(RunAssembly) {
            timeEquipment() >> VirtualTimeEquipment.create()
        }, git, Stub(Tracker), CLONE, containers)
        ClaimlessDeliveredCleanup.forSlot(new SlotWiring(wiring.assembly(), wiring.git(), wiring.registeredClone(),
                wiring.taskIdMdcKey(), wiring.abort(), ['GH_TOKEN'], wiring.containerTakeSupport(), wiring.tenure(),
                wiring.trustedBase()), tracker)
    }
}
