package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * `tracker-take` "Revocation detected at round boundaries", container mode: what a revoked
 * sandboxed run leaves behind. The salvage commit is made inside the environment and harvested,
 * the push runs factory-side after it ({@link SandboxRunSupport#revocationSalvageAndPush} does
 * both, in that order), and the environment is then left as the claim loss found it — neither
 * kept stopped nor disposed by the run — for the lifecycle sweep, which classifies it unowned and
 * stops it (design D3).
 *
 * <p>Driven on port fakes over the real {@link TakeContainerEngineExecution}, as its sibling
 * {@code TakeContainerEngineExecutionSpec} is.
 *
 * <p>FR15 of add-tracker-port; FR1 of add-serve-sandbox-lifecycle.
 */
class TakeContainerRevocationSpec extends Specification implements RunChainFakes {

    // FR15: the round-boundary check finds the task no longer ours (Gone), so the run is revoked.
    // F75: scenario text aligned to what this test asserts
    // Scenario: tracker/tracker-take / Revoked sandboxed task keeps a stopped environment
    def "a revoked sandboxed run salvages, pushes after the harvest, and leaves the environment to the sweep"() {
        given:
        def tracker = Mock(Tracker)
        tracker.fetchTask(_) >> trackerTask(new TrackerTaskState.Gone())
        def support = Mock(SandboxRunSupport) {
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> new FakeWorkspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }
        def execution = new TakeContainerEngineExecution(
                assemblyRunning(new ScriptedExecutor([completedRound()])),
                Stub(TaskBranchGit),
                new TakeOutcomeDispatch(VirtualTimeRetries.terminalWrite(), new AbortFuse(new AbortHandler(tracker, FIXED_CLOCK), 3)),
                [], new ClaimLossFlag(), LawBinding.atRevision(CLONE_DIR, GitObjects.HEAD))

        when:
        def result = execution.run(
                takeOrder(heldByUs(), tracker, runOrder(completingPipeline())), support, new BranchAdoption.AlreadyLocal('PROJ-1'),
                new TaskContext('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                TaskState.atStageStart('build'), null)

        then: 'leftovers are salvage-committed in the environment, harvested, and pushed factory-side'
        1 * support.revocationSalvageAndPush('PROJ-1')

        then: 'the environment is left for the sweep to stop — neither stopped nor disposed by the run'
        0 * support.keepStopped()
        0 * support.completeAndDispose(_)
        0 * support.disposeExistingEnvironment()

        and:
        result instanceof TakeResult.Revoked
    }
}
