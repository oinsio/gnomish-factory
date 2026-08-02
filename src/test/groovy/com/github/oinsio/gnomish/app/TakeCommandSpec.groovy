package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, FR10, FR17 of add-tracker-port (task 5.13): {@link TakeCommand} end to end — pipeline
 * load, the FR17 no-tracker-section refusal, the unknown-adapter-type refusal, explicit-mode
 * dispatch reaching {@link TakeDisposition} with a correctly-fetched {@link TrackerTask}, and
 * bare-mode dispatch reaching {@link TakeBareAuto}, each converted to the right
 * {@link TakeExitCodeException}.
 */
class TakeCommandSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final TaskRef REF = new TaskRef('github:acme/widgets#42')
    private static final String INSTANCE_NAME = 'gnomish-factory'

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Tracker tracker = Mock()

    // FR6 of add-factory-serve: mutable so a test can set open fronts before command.run() runs,
    // read lazily (the closure form) so the per-test override takes effect over this default
    List<com.github.oinsio.gnomish.app.port.tracker.OpenTask> openTasks = []

    def setup() {
        // A real git repo (not just a bare .gnomish/ tree): explicit-mode's fresh claim goes
        // through TakeFreshClaim -> GitFreshTaskSupport, which creates a task branch off the
        // clone's current HEAD (mirrors TakeResumeSpecBase's own setup).
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        commitAll(projectDir)
        worktreesRoot = tempDir.resolve('worktrees')
        // FR6, D5 of add-factory-serve: TakeBareAuto reads the open-front count unconditionally now
        // (FeedPolicy snapshot + OpenFrontGate per-claim re-check); default to no open fronts so
        // specs unconcerned with the WIP limit are unaffected.
        tracker.listOpen() >> { openTasks }
    }

    /** Writes config.yaml with the given tracker section appended verbatim (FR17). */
    private void writeConfig(String trackerSection = '') {
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                "schemaVersion: \"1\"\nautonomy:\n  attemptLimit: 3\n$trackerSection")
    }

    private static TrackerAdapterFactory fakeFactory(Tracker t) {
        new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        t
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }
                }
    }

    private TakeCommand newCommand(Map<String, TrackerAdapterFactory> registry) {
        TakeCommandFactory.of(
                newAssembly(testProperties(instanceName: INSTANCE_NAME)),
                worktreesRoot,
                'taskId',
                testProperties(instanceName: INSTANCE_NAME),
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                registry,
                TrackerValidatorStub.acceptingGithub())
    }

    private static DefaultApplicationArguments args(String... raw) {
        new DefaultApplicationArguments(raw)
    }

    def "no tracker section in config.yaml refuses with UsageException (FR17)"() {
        given:
        writeConfig()
        def command = newCommand([:])

        when:
        command.run(args('take', '42', "--dir=$projectDir"))

        then:
        def ex = thrown(UsageException)
        ex.message.contains('tracker')
    }

    def "unknown tracker.type refuses with UsageException before touching the tracker"() {
        given:
        writeConfig('''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        def command = newCommand([:])

        when:
        command.run(args('take', '42', "--dir=$projectDir"))

        then:
        def ex = thrown(UsageException)
        ex.message.contains('github')
        0 * tracker._
    }

    def "explicit mode fetches the task from the resolved tracker and dispatches to TakeDisposition"() {
        given:
        writeConfig('''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        tracker.fetchTask(_) >> new TrackerTask(
                REF, new TaskSnapshot('PROJ-1', 'title', 'body'), new TrackerTaskState.Finished(), AbortFacts.none())
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15 // Finished -> Skipped ("already done") per TakeDisposition
    }

    def "explicit mode delivering a fresh Ready task exits 0 (Delivered)"() {
        given:
        writeConfig('''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        // First fetchTask (TakeCommand's own, before dispatch) reports Ready; every later
        // fetchTask (TakeClaimAndWork's post-claim revocation checks) must report THIS run's
        // own, randomly-suffixed InstanceId still holding the claim, or RevocationCheckingAttempt
        // Persistence treats it as revoked — capture the claiming instance id from the claim()
        // call itself (mirrors TakeResumeSpecBase's fetchTask stub shape, but holder is dynamic
        // here since TakeCommand mints a fresh InstanceId per invocation).
        String claimedBy = null
        tracker.claim(_, _) >> { TaskRef ref, String instanceId -> claimedBy = instanceId; new ClaimResult.Acquired() }
        tracker.fetchTask(_) >> {
            new TrackerTask(
            REF, new TaskSnapshot('PROJ-1', 'title', 'body'),
            claimedBy == null ? new TrackerTaskState.Ready() : new TrackerTaskState.Working(claimedBy),
            AbortFacts.none())
        }
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir", '--interactive'))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 0
    }

    // FR9, design D8 of add-tracker-port: a full canonical id whose repo the adapter refuses to
    // reconcile to the configured binding is refused (exit 15) BEFORE fetchTask ever runs — the
    // foreign repo is never touched. Proves TakeCommand wires refuseForeignRef into the explicit
    // path (the refusal message itself is the GitHub adapter's concern, covered by its own spec).
    def "explicit mode refuses a foreign canonical id (exit 15) before fetching the task"() {
        given:
        writeConfig('''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        def factory = new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        tracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }

                    Optional<String> refuseForeignRef(
                            TrackerConfig config, TaskRef ref) {
                        Optional.of("Task id names repo other/repo but the factory is configured for acme/widgets")
                    }
                }
        def command = newCommand([github: factory])

        when:
        command.run(args('take', 'github:other/repo#7', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15
        0 * tracker.fetchTask(_)
    }

    def "bare mode with an empty ready queue dispatches to TakeBareAuto and exits 0 (EmptyQueue)"() {
        given:
        writeConfig('''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        tracker.listReady(_) >> List.<ReadyTask> of()
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 0
        0 * tracker.fetchTask(_)
    }

    def "bare mode skipped when every eligible candidate loses the claim race exits 15 (Skipped)"() {
        given:
        writeConfig('''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        tracker.listReady(_) >> [
            new ReadyTask(REF, AbortFacts.none(), false)
        ]
        tracker.claim(REF, _) >> new ClaimResult.Held('someone-else')
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15
    }

    // FR6 of add-factory-serve (task 3.1): a configured wip-limit lower than design D3's default of
    // 10 blocks a fresh claim once open fronts reach it, proving TakeDispatcher.runBare sources the
    // limit from the parsed tracker.wip-limit config rather than a hardcoded default
    def "bare mode blocked by a configured wip-limit below the design default (FR6)"() {
        given:
        writeConfig('''
tracker:
  type: github
  wip-limit: 1
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        openTasks = [
            new com.github.oinsio.gnomish.app.port.tracker.OpenTask(
            new TaskRef('github:acme/widgets#1'),
            new TrackerTaskState.Working('someone-else'), null)
        ]
        tracker.listReady(_) >> [
            new ReadyTask(REF, AbortFacts.none(), false)
        ]
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15
        0 * tracker.claim(_, _)
    }

    // FR9 of add-tracker-port (task 5.14): explicit mode with a short ref ('42') calls into the
    // registered factory's expandRef, proving TakeCommand's wiring end to end without touching the
    // real GitHub adapter (a fake TrackerAdapterFactory stands in for it)
    def "explicit mode with a short ref expands via the registered factory's expandRef"() {
        given:
        writeConfig('''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        tracker.fetchTask(REF) >> new TrackerTask(
                REF, new TaskSnapshot('PROJ-1', 'title', 'body'), new TrackerTaskState.Finished(), AbortFacts.none())
        def factory = new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        tracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        assert rawRef == '42'
                        assert config.type() == 'github'
                        REF
                    }
                }
        def command = newCommand([github: factory])

        when:
        command.run(args('take', '42', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15 // Finished -> Skipped, proves fetchTask was called with the expanded REF
    }

    // FR9 of add-tracker-port: a short ref refuses cleanly when no adapter factory is registered
    // (the same "unknown tracker.type" refusal covers this — resolveTracker fails before
    // resolveExplicitRef is ever reached, since both are keyed by the same registry lookup), so
    // there is no way to construct a registry where 'create' resolves but 'expandRef' doesn't;
    // this is exercised by "unknown tracker.type refuses with UsageException before touching the
    // tracker" above, which already uses ref '42' (a short ref) against an empty registry.
    def "explicit mode with an already-canonical ref is not treated as a short ref, even numeric-looking"() {
        given:
        writeConfig('''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        tracker.fetchTask(REF) >> new TrackerTask(
                REF, new TaskSnapshot('PROJ-1', 'title', 'body'), new TrackerTaskState.Finished(), AbortFacts.none())
        // No expandRef call expected: registry has ONLY 'create' wired via the single-closure
        // coercion; if resolveExplicitRef wrongly tried to expand this canonical ref it would
        // invoke the closure for expandRef too, returning 'tracker' (not a TaskRef) and blowing
        // up with a ClassCastException instead of the expected TakeExitCodeException.
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15
    }
}
