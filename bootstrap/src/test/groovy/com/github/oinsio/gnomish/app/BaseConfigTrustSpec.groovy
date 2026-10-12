package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitBaseRefs
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.VirtualTimeGitRetries
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.FixedTrackerAdapterFactory
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR6, FR7, NFR-S1 of add-base-ref-resolution, against a real local bare {@code origin} and a
 * real {@link TakeCommand}: where the base configuration comes from (the refreshed default branch,
 * never a gnome's branch) and what a crashed resolution leaves behind (nothing a pickup reads).
 * The plumbing is {@link BaseRefBareRemoteIntegrationSpec}'s, which this file would push past the
 * size cap.
 */
class BaseConfigTrustSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    private static final String ALLOW_RELEASE = '''\
task-branch:
  base:
    allowed:
      - pattern: "release/*"
        role: release
'''

    @TempDir
    Path tempDir

    Path projectDir
    Path originDir
    String mainBranch
    RegisteredClone registeredClone
    InMemoryTracker tracker = new InMemoryTracker()
    InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/build/instructions.md
advancement: auto
''')
        writeConfig(projectDir)
        commitAll(projectDir)
        originDir = addOrigin(projectDir, tempDir)
        mainBranch = currentBranch(projectDir)
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), projectDir)
    }

    private static void writeConfig(Path repo, String taskBranchSection = '') {
        Files.writeString(repo.resolve('.gnomish/config.yaml'),
                "schemaVersion: \"1\"\nautonomy:\n  attemptLimit: 3\ntracker:\n  type: github\n" +
                "  github:\n    api-url: https://api.github.com\n    repo: acme/widgets\n$taskBranchSection")
    }

    private void take(String taskId) {
        def factoryProperties = FakeAgentSupport.propertiesFor('plain-round')
        def command = TakeCommands.of(
                newAssembly(factoryProperties, TakeCommands.slotTime()), TaskGitFixture.real(), registeredClone, 'taskId', factoryProperties,
                new TrackerWiring([github: new FixedTrackerAdapterFactory({
                        tracker
                    })], MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create()),
                SandboxLifecyclePass.NONE, ContainerTakeSupportFixture.hostOnly())
        try {
            command.run(args('take', taskId, "--dir=$projectDir"))
        } catch (TakeExitCodeException ignored) {
            // Every take ends by throwing this to carry its exit code; the branch is what is asserted.
        }
    }

    private void seedReady(String taskId, TaskDesignators designators = TaskDesignators.none()) {
        def ref = new TaskRef(taskId)
        harness.seed(ref, new TaskSnapshot(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body')), new TrackerTaskState.Ready(), AbortFacts.none())
        if (!designators.byKind().isEmpty()) {
            harness.seedDesignators(ref, designators)
        }
    }

    private boolean hasBranch(String taskId) {
        gitExitCode(projectDir, 'rev-parse', '--verify', '-q', "refs/heads/gnomish/${taskId}") == 0
    }

    /** task.json of the branch's creation commit — the pin is written there once (FR7). */
    private TaskRecord creationRecord(String taskId) {
        def created = gitOutput(projectDir, 'log', '--format=%H', '--reverse', "gnomish/${taskId}", '--', '.gnomish-task/task.json').readLines().first()
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(UntrustedText.branchDocument(gitOutput(projectDir, 'show', "${created}:.gnomish-task/task.json"))))
    }

    private void pushReleaseBranch() {
        gitOutput(projectDir, 'checkout', '-b', 'release/1.18')
        commit(projectDir, 'release-note.txt', 'release 1.18\n')
        assert gitExitCode(projectDir, 'push', '-q', 'origin', 'release/1.18:refs/heads/release/1.18') == 0
        gitOutput(projectDir, 'checkout', mainBranch)
    }

    // FR2, NFR-S1 of add-base-ref-resolution: the section is read from the refreshed default branch
    //     only; a gnome branch that edits it is a branch like any other until a human merges it.
    // Scenario: git/base-ref-resolution / A gnome edit to the task-branch section has no effect
    def "FR2: a gnome branch allowing release/* changes nothing until the edit is merged to the default branch"() {
        given: 'a gnome branch on origin whose working copy allows release/*, and a release/1.18 branch'
        gitOutput(projectDir, 'checkout', '-b', 'gnomish/GNOME-1')
        writeConfig(projectDir, ALLOW_RELEASE)
        commitAll(projectDir, 'gnome edits the base section')
        assert gitExitCode(projectDir, 'push', '-q', 'origin', 'gnomish/GNOME-1') == 0
        gitOutput(projectDir, 'checkout', mainBranch)
        pushReleaseBranch()

        when: 'a task selecting release/1.18 is claimed'
        seedReady('PROJ-7', TaskDesignators.of('base', Designator.single('release/1.18')))
        take('PROJ-7')

        then: 'it resolved under the default branch, which allows nothing: parked with a report naming the base, no task branch'
        !hasBranch('PROJ-7')
        harness.threadAsStrings(new TaskRef('PROJ-7')).any {
            it.contains('release/1.18')
        }

        when: 'a human merges the edit to the default branch and the next task is claimed'
        writeConfig(projectDir, ALLOW_RELEASE)
        commitAll(projectDir, 'merge the base section')
        pushOrigin(projectDir)
        seedReady('PROJ-8', TaskDesignators.of('base', Designator.single('release/1.18')))
        take('PROJ-8')

        then: 'the merged section is the one that participates'
        creationRecord('PROJ-8').pin().ref() == 'release/1.18'
        creationRecord('PROJ-8').pin().rule() == BaseRule.DESIGNATOR
    }

    // FR6, FR7 of add-base-ref-resolution: the refresh writes only the base's own fetch destination
    //     and the pin lands in the creation commit, so a resolution that died between the two left
    //     nothing a later pickup reads.
    // Scenario: git/base-ref-resolution / A crash before the pin re-resolves cleanly
    def "FR7: an instance that refreshed the base and died is followed by a pickup that resolves from scratch"() {
        given: 'an instance that refreshed the default branch, then died before creating the task'
        seedReady('PROJ-9')
        def first = new GitBaseRefs(new GitProcessRunner(), VirtualTimeGitRetries.gitInfrastructure()).refresh(projectDir, mainBranch)
        def firstTip = (first as BaseRefreshOutcome.Refreshed).commit()
        assert !hasBranch('PROJ-9')

        and: 'origin moves on before anyone picks the task up'
        commit(projectDir, 'advance.txt', 'advance\n')
        pushOrigin(projectDir)
        def advancedTip = gitOutput(originDir, 'rev-parse', 'HEAD')
        assert advancedTip != firstTip

        when:
        take('PROJ-9')

        then: 'the pickup resolved again, to the newer tip, and pinned and started from it'
        def record = creationRecord('PROJ-9')
        record.baseCommit() == advancedTip
        record.pin().rule() == BaseRule.REPOSITORY_DEFAULT_BRANCH
        def created = gitOutput(projectDir, 'log', '--format=%H', '--reverse', 'gnomish/PROJ-9', '--', '.gnomish-task/task.json').readLines().first()
        gitOutput(projectDir, 'rev-parse', "${created}^") == advancedTip

        and: "the first resolution's fetch destination was overwritten, not kept"
        gitOutput(projectDir, 'rev-parse', "refs/remotes/origin/${mainBranch}") == advancedTip
    }
}
