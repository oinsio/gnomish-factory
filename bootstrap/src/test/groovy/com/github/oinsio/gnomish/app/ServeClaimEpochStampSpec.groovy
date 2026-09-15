package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import java.nio.file.Path
import java.time.Clock
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The serve half of the tenure identity (FR4, FR6 of fix-claim-epoch-fence): one real {@code
 * gnomish serve --drain} pass over a real git project and a real {@link InMemoryTracker}, asserting
 * that the commits the round pushed carry the very epoch the tracker issued for that claim.
 *
 * <p>The take path has this assertion twice already ({@code TakeLifecycleEscalateResumeSpecBase},
 * {@code TakeLifecycleCrashReapReclaimSpecBase}), but {@code ServeCommand.provisionTracker} is a
 * SECOND call site of the claiming funnel {@code TrackerResolution.resolveTracker}, wired from its
 * own bundle ({@code git.epochs()}). Nothing in the take specs reaches that line: a serve that
 * resolved its tracker over a book its git writers do not read would record every claim into a
 * record nobody stamps from, leaving unstamped commits and a green build. This spec is what fails
 * instead.
 *
 * <p>The epoch is read off the port's own answer ({@link ClaimWatchingTrackerFactory}) and the
 * stamp off the real commit ({@code BareGitRepoFixture.stampOf}) — no book is wired by the test,
 * so the identity is asserted end to end on the real medium rather than between two test doubles.
 *
 * <p>Implements FR3, FR4, FR6 of fix-claim-epoch-fence.
 */
@Timeout(120)
class ServeClaimEpochStampSpec extends Specification
implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final String TASK_BRANCH = 'gnomish/PROJ-1'

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Path homeDir
    InMemoryTracker tracker = new InMemoryTracker()
    ClaimWatchingTrackerFactory claimWatcher

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        writeMinimalProject(projectDir)
        commitAll(projectDir)
        // FR5, FR13 of add-base-ref-resolution: a real serve startup resolves and refreshes its
        // base against a real 'origin' remote, never the clone's local HEAD.
        addOrigin(projectDir, tempDir)
        worktreesRoot = tempDir.resolve('worktrees')
        homeDir = tempDir.resolve('home')
        claimWatcher = new ClaimWatchingTrackerFactory(fakeFactory(tracker))
        new InMemoryTrackerHarness(tracker).seed(
                REF, new TaskSnapshot(REF.id(), 'Add widgets', 'please add widgets'),
                new TrackerTaskState.Ready(), AbortFacts.none())
    }

    private ServeCommand newCommand() {
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        new ServeCommand(
                newAssembly(properties),
                TaskGitFixture.real(),
                worktreesRoot,
                homeDir,
                'taskId',
                properties,
                new ServeProperties(1, null, null, null, null, null, null, null, null),
                Clock.systemUTC(),
                new SystemClock(),
                [github: claimWatcher],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(),
                new RefusingStarter(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly(),
                LiveConsoleIO.onStderr())
    }

    def "FR6: the tip a serve round delivered carries the epoch the tracker issued for that claim"() {
        when: 'a real serve drain pass claims, works and delivers the seeded task'
        newCommand().run(args('serve', "--dir=$projectDir", '--drain'))

        then: 'the round really did deliver — an unstamped branch nobody wrote proves nothing'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'exactly one claim was made, and it is the tenure every commit below belongs to'
        claimWatcher.issuedEpochs.size() == 1

        and: 'the task branch tip carries that very epoch (FR3, FR4)'
        def epoch = claimWatcher.issuedEpochs[0]
        stampOf(projectDir, TASK_BRANCH) == epoch

        and: 'and so does every commit the tenure made, not only the last one'
        def tenureCommits = commitsIn(projectDir, "origin/${currentBranch(projectDir)}..${TASK_BRANCH}")
        !tenureCommits.isEmpty()
        tenureCommits.every { stampOf(projectDir, it) == epoch }
    }
}
