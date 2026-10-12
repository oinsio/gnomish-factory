package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.lease.BlockingSleeper
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The shared assembly of the {@code take} heartbeat proofs (task 6.1 of add-claim-heartbeat, FR1):
 * a REAL tracker adapter, a real local git repo with an origin, and a {@link TakeCommand} whose beat
 * interval sleeper is a controllable {@link BlockingSleeper}, so a spec drives beats one tick at a
 * time with no real sleeping. The build stage's executor block takes extra lines from {@link
 * #stageSettings} (a {@code roundTimeout}, for one).
 *
 * <p>The standing reaper (fix-reaper-idle-liveness) gets its OWN {@link BlockingSleeper}, separate
 * from the beat's — no spec drives it, so the reaper thread parks on it forever, quietly, and can
 * never steal a release meant for the beat thread.
 *
 * <p>Abstract for the same reason as {@link TakeLifecycleReadyToDeliveredSpecBase}: a concrete
 * adapter's seeding and thread-reading name the concrete adapter type, so they live in a subclass
 * inside {@code adapter.tracker} while this base — which constructs the package-private {@link
 * TakeCommand}/{@link ManualRunAssembly} — stays in {@code app} and touches the tracker only
 * through the {@link Tracker} port. {@link #seededReadyTrackerAndFactory} and {@link #heartbeatCount}
 * are the two seams a subclass fills in.
 *
 * <p>Implements FR1 of add-claim-heartbeat.
 */
abstract class TakeHeartbeatFixtureBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')

    @TempDir
    Path tempDir

    Path projectDir
    RegisteredClone registeredClone
    Tracker tracker
    TrackerAdapterFactory trackerFactory
    BlockingSleeper sleeper
    // The standing reaper's own sleeper (fix-reaper-idle-liveness FR5): never driven by this spec, so
    // the reaper thread parks on it forever and can never steal a release meant for the beat thread.
    BlockingSleeper reaperSleeper

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@link #REF} */
    abstract List seededReadyTrackerAndFactory(TaskRef ref, String title, String body)

    /** @return how many heartbeat beats the tracker has recorded on {@code ref}'s claim so far */
    abstract int heartbeatCount(Tracker tracker, TaskRef ref)

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory
        sleeper = new BlockingSleeper()
        reaperSleeper = new BlockingSleeper()

        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), """\
purpose: build it
executor:
  type: agent-cli
  model: claude-fake-main-1
${stageSettings()}instructions: stages/build/instructions.md
advancement: auto
""")
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                '''\
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
        // FR5, FR13 of add-base-ref-resolution: a real take startup/fresh-claim resolves and
        // refreshes its base against a real 'origin' remote, never the clone's local HEAD.
        addOrigin(projectDir, tempDir)
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), projectDir)
    }

    /** Extra lines of the build stage's executor block, indented under it; none by default. */
    protected String stageSettings() {
        ''
    }

    protected TakeCommand newCommand(FactoryProperties factoryProperties) {
        TakeCommands.of(
                newAssembly(factoryProperties, TakeCommands.slotTime()),
                TaskGitFixture.real(),
                registeredClone,
                'taskId',
                factoryProperties,
                new TrackerWiring([github: trackerFactory], MapSecretsProvider.NONE, TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create()), { time ->
                    TakeCommandSeams.defaults(new TimeEquipment(time.clock(), sleeper), LiveConsoleIO.onStdout()).withReaperSleeper(reaperSleeper)
                }, SandboxLifecyclePass.NONE, ContainerTakeSupportFixture.hostOnly())
    }
}
