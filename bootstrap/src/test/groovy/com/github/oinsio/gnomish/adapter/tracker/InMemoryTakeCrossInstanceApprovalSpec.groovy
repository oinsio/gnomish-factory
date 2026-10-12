package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.TakeExitCodeException
import com.github.oinsio.gnomish.app.TwoInstanceTakeFixture
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * `tracker-take` "Any instance can pick up a returned task", for a checkpoint: instance A parks the
 * task at the gate of a {@code manual} stage, a human returns it, and instance B — a freshly built
 * factory sharing only the tracker and the registered clone with A — approves the gate on the
 * branch and runs the next stage. Two stages, both played by the fake agent, no checks.
 *
 * <p>FR13 of add-tracker-port; FR4 of make-checkpoint-gate-durable.
 */
class InMemoryTakeCrossInstanceApprovalSpec extends Specification implements TwoInstanceTakeFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final String BRANCH = 'gnomish/PROJ-1'

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
        writeGatedProject()
    }

    /** {@code build} (manual: a passing pass parks at its gate), then {@code ship} (auto). */
    private void writeGatedProject() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.writeString(Files.createDirectories(projectDir.resolve('.gnomish')).resolve('pipeline.yaml'),
                'stages:\n  - build\n  - ship\n')
        ['build': 'manual', 'ship': 'auto'].each { name, advancement ->
            def dir = Files.createDirectories(projectDir.resolve(".gnomish/stages/${name}"))
            Files.writeString(dir.resolve('instructions.md'), "${name} it\n")
            Files.writeString(dir.resolve('stage.yaml'), """\
purpose: ${name} it
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/${name}/instructions.md
advancement: ${advancement}
""")
        }
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(projectDir)
        addOrigin(projectDir, tempDir)
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), projectDir)
    }

    /** One take by a fresh instance whose gnome records each prompt in {@code prompts}. */
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

    // FR13: the approval is a write on the branch, so the instance that makes it needs nothing from
    // the instance that parked.
    // Scenario: tracker/tracker-take / Cross-instance approval
    def "a checkpoint parked by one instance is approved and continued by another"() {
        given: 'instance A passes build and parks at its gate'
        assert take('instance-a', capture('a')) == 11
        assert tracker.fetchTask(REF).state() instanceof TrackerTaskState.AwaitingHuman
        def parkedTip = gitOutput(projectDir, 'rev-parse', BRANCH)

        when: 'a human returns the task, and instance B takes it'
        harness.returnToReady(REF)
        def bPrompts = capture('b')
        def exit = take('instance-b', bPrompts)

        then: 'B delivered the task'
        exit == 0
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'B wrote the approval on the branch, as the first commit of its tenure'
        def bCommits = commitsSince(parkedTip, BRANCH)
        subjectOf(bCommits.first()) == 'gnomish: task approved'
        stampOf(bCommits.first()) != stampOf(parkedTip)

        and: 'and continued with the following stage only: build was not run again'
        promptsIn(bPrompts).size() == 1
        promptsIn(bPrompts).first().contains('ship it')
    }
}
