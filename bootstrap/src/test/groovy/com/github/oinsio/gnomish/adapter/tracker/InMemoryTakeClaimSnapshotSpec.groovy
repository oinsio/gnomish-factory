package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeExitCodeException
import com.github.oinsio.gnomish.app.TwoInstanceTakeFixture
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * `tracker-take` over the escalating two-instance fixture ({@link TwoInstanceTakeFixture}: one
 * stage whose {@code files_exist} check fails until the file is put into the shared worktree) and
 * one shared {@link InMemoryTracker}: what a run reads from the issue after its first claim, and
 * what quality attempts cost the abort accounting. Each take is a freshly built factory instance
 * whose gnome is the fake agent with its prompt captured.
 *
 * <p>FR11, FR12 of add-tracker-port; FR14 of harden-task-branch-contract.
 */
class InMemoryTakeClaimSnapshotSpec extends Specification implements TwoInstanceTakeFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')

    @TempDir
    Path tempDir

    private InMemoryTrackerHarness harness

    def setup() {
        def inMemory = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(inMemory)
        harness.seed(REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('Add widgets'), UntrustedText.tracker('please add widgets')),
                new TrackerTaskState.Ready(), AbortFacts.none())
        tracker = inMemory
        trackerFactory = new FixedTrackerAdapterFactory({ inMemory })
        writeTwoInstanceProjectFixture()
    }

    /** Runs one take as a fresh instance whose gnome appends every prompt it is given to {@code prompts}. */
    private int take(String instanceName, Path prompts) {
        def properties = testProperties(instanceName: instanceName,
        agentCliBinary: FakeAgentSupport.binaryCapturingStdin('plain-round', prompts))
        try {
            newTakeCommand(properties, registeredClone, [github: trackerFactory]).run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))
            throw new IllegalStateException('take returned without an exit code')
        } catch (TakeExitCodeException e) {
            e.exitCode()
        }
    }

    /** The prompt capture of one instance: the fake agent names it after its scenario. */
    private Path capture(String instance) {
        Files.createDirectories(tempDir.resolve(instance)).resolve('plain-round.log')
    }

    private static List<String> promptsIn(Path capture) {
        Files.readString(capture).split(/(?m)^---$/).collect {
            it.strip()
        }.findAll {
            !it.isEmpty()
        }
    }

    // FR11: the snapshot taken at the first claim is the task's description for the task's life.
    // Scenario: tracker/tracker-take / Issue edited mid-task
    def "an issue edited while the task is parked changes neither the gnome's context nor task.json"() {
        given: 'instance A claims the task, snapshots the issue, and parks it on an escalation'
        assert take('instance-a', capture('a')) == 10

        when: 'a human rewrites the issue body, answers, and returns the task'
        harness.edit(REF, 'Add widgets', 'REWRITTEN after the claim')
        harness.reply(REF, 'please retry', Instant.parse('2026-01-01T00:00:01Z'))
        harness.returnToReady(REF)
        Files.writeString(registeredClone.worktrees().resolve('PROJ-1').resolve('missing-file.txt'), 'fixed\n')

        and: 'instance B resumes it'
        def bPrompts = capture('b')
        def exit = take('instance-b', bPrompts)

        then:
        exit == 0

        and: 'the resumed gnome worked from the claim-time snapshot plus the collected decision'
        def prompt = promptsIn(bPrompts).first()
        prompt.contains('please add widgets')
        prompt.contains('please retry')
        !prompt.contains('REWRITTEN')

        and: 'the task.json the resume committed still carries the claim-time snapshot'
        def resumed = commitsIn(projectDir, 'gnomish/PROJ-1').find {
            subjectOf(it) == 'gnomish: task resumed'
        }
        def taskJson = gitOutput(projectDir, 'show', "${resumed}:.gnomish-task/task.json")
        taskJson.contains('please add widgets')
        !taskJson.contains('REWRITTEN')
    }

    // FR14 of harden-task-branch-contract: verification failures are stage attempts, never recovery.
    // Scenario: tracker/tracker-take / Quality failures never burn the recovery budget
    def "stage-verification failures within the attempt limit leave the recovery and crash counts unchanged"() {
        given: 'the stage allows two attempts, both of which fail their check'
        def stageYaml = projectDir.resolve('.gnomish/stages/build/stage.yaml')
        Files.writeString(stageYaml, Files.readString(stageYaml).replace('attemptLimit: 1', 'attemptLimit: 2'))
        commitAll(projectDir, 'two attempts')
        pushOrigin(projectDir)
        def prompts = capture('a')

        when:
        def exit = take('instance-a', prompts)

        then: 'two quality attempts ran, then the stage escalated for a human'
        promptsIn(prompts).size() == 2
        exit == 10

        and: 'neither the crash count nor the recovery count moved, and no abort was recorded'
        def facts = tracker.fetchTask(REF).abortFacts()
        facts.count() == 0
        facts.recoveryCount() == 0
        harness.threadAsStrings(REF).every { !it.startsWith('ABORT:') }
    }
}
