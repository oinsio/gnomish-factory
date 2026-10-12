package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR1 of add-factory-serve: a {@code serve} slot runs the take cycle unchanged. Two identical
 * worlds — a real project with a real origin, a real {@link InMemoryTracker}, the fake agent —
 * receive the same task, whose only stage fails its single attempt and escalates. One world is
 * worked by {@code serve --drain}, the other by a single {@code take}; what each leaves behind on
 * the tracker and on the task branch is compared field by field.
 */
@Timeout(120)
class ServeSlotBodySpec extends Specification
implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')

    @TempDir
    Path tempDir

    /** One world: a project whose single stage escalates on its first attempt, and a tracker holding the task. */
    private Map world(String name) {
        def root = Files.createDirectories(tempDir.resolve(name))
        def projectDir = initWorkingRepo(root, 'project')
        writeMinimalProject(projectDir)
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
verify:
  - type: builtin
    name: files_exist
    params:
      files:
        - missing-file.txt
advancement: auto
autonomy:
  attemptLimit: 1
''')
        commitAll(projectDir)
        addOrigin(projectDir, root)
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        harness.seed(REF, new TaskSnapshot(REF.id(), UntrustedText.tracker('Add widgets'), UntrustedText.tracker('please')),
                new TrackerTaskState.Ready(), AbortFacts.none())
        [projectDir: projectDir, home: root.resolve('home'), tracker: tracker, harness: harness]
    }

    private void serveDrain(Map w) {
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        newDrainCommand(properties, newAssembly(properties), RegisteredCloneFixture.unregistered(w.home, w.projectDir),
                fakeFactory(w.tracker)).run(args('serve', "--dir=${w.projectDir}", '--drain'))
    }

    private void take(Map w) {
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        def command = TakeCommands.of(newAssembly(properties, TakeCommands.slotTime()), TaskGitFixture.real(),
                RegisteredCloneFixture.unregistered(w.home, w.projectDir), 'taskId', properties,
                new TrackerWiring([github: fakeFactory(w.tracker)], MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create()),
                SandboxLifecyclePass.NONE, ContainerTakeSupportFixture.hostOnly())
        try {
            command.run(args('take', REF.id(), "--dir=${w.projectDir}"))
        } catch (TakeExitCodeException ignored) {
            // every take ends by carrying its exit code; what it left behind is what is compared
        }
    }

    /** The task's tracker thread, each claiming process's per-run id suffix blanked. */
    private static List<String> thread(Map w) {
        w.harness.threadAsStrings(REF).collect {
            it.replaceAll(/(claimed by \S+)-[0-9a-z]{6}/, '$1-<run>')
        }
    }

    /** task.json at the tip of the task branch: the park's durable record. */
    private TaskRecord record(Map w) {
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(UntrustedText.branchDocument(
                        gitOutput(w.projectDir, 'show', "gnomish/${REF.id()}:.gnomish-task/task.json"))))
    }

    // Scenario: serve/factory-serve / Slot body unchanged
    def "FR1: a slot's escalation parks, reports and records exactly as a single take of the task does"() {
        given:
        def served = world('served')
        def taken = world('taken')

        when:
        serveDrain(served)
        take(taken)

        then: 'the tracker holds both tasks in the same parked state'
        def parked = served.tracker.fetchTask(REF).state()
        parked instanceof TrackerTaskState.AwaitingHuman
        parked == taken.tracker.fetchTask(REF).state()

        and: 'the thread is the same, entry for entry — only the claiming process\'s own id differs'
        thread(served) == thread(taken)

        and: 'the branch records the same escalation, its tracker write confirmed in both'
        def servedRecord = record(served)
        def takenRecord = record(taken)
        servedRecord.lastEscalation() == takenRecord.lastEscalation()
        servedRecord.outcome().getClass() == takenRecord.outcome().getClass()
        !servedRecord.trackerWritePending()
        !takenRecord.trackerWritePending()
    }
}
