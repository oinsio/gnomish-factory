package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.FixedTrackerAdapterFactory
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.baseref.BaseRule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Task 7.6 of add-base-ref-resolution: an integration proof against a real local bare {@code
 * origin} — the fixture template {@link TakeLifecycleReadyToDeliveredSpecBase} already
 * establishes for a real {@link TakeCommand} run — that a fresh task branch's durable base pin
 * (FR7, {@code task.json}'s {@code baseRef}/{@code baseCommit}/{@code baseRule}) really comes from
 * the remote, never from the clone's local checkout.
 *
 * <p>Two features share the same real-git plumbing and differ only in tracker seeding, per the
 * task's own guidance: zero-config (M1) has no {@code task-branch.base} section and no designator,
 * so resolution falls through to the repository default branch; the label-selected release base
 * (U2) declares an {@code allowed} pattern and seeds a {@code base} designator via {@link
 * InMemoryTrackerHarness#seedDesignators} — the established, tracker-agnostic seam every unit spec
 * in this codebase already uses instead of a real GitHub label round trip.
 *
 * <p>Implements M1, U2 of add-base-ref-resolution.
 */
class BaseRefBareRemoteIntegrationSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    private static final String PROJECT_NAME = 'project'

    @TempDir
    Path tempDir

    Path projectDir
    Path originDir
    Path worktreesRoot
    InMemoryTracker tracker = new InMemoryTracker()
    InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    def setup() {
        projectDir = initWorkingRepo(tempDir, PROJECT_NAME)
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
        commitAll(projectDir)
        // FR5, FR13 of add-base-ref-resolution: a real take startup/fresh-claim resolves and
        // refreshes its base against a real 'origin' remote, never the clone's local HEAD.
        originDir = addOrigin(projectDir, tempDir)
        worktreesRoot = tempDir.resolve('worktrees')
    }

    /** Writes config.yaml with the given task-branch section appended verbatim, commits it, and re-pushes it to origin
     * so a real take startup reads its trusted tier from git objects at origin's refreshed tip (FR13, D14). */
    private void writeConfig(String taskBranchSection = '') {
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                "schemaVersion: \"1\"\nautonomy:\n  attemptLimit: 3\ntracker:\n  type: github\n" +
                "  github:\n    api-url: https://api.github.com\n    repo: acme/widgets\n$taskBranchSection")
        commitAll(projectDir, 'config')
        pushOrigin(projectDir)
    }

    private TakeCommand newCommand() {
        def factoryProperties = FakeAgentSupport.propertiesFor('plain-round')
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                TaskGitFixture.real(),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: new FixedTrackerAdapterFactory({ tracker })],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly())
    }

    private void seedReady(TaskRef ref, TaskDesignators designators = TaskDesignators.none()) {
        harness.seed(ref, new TaskSnapshot(ref.id(), 'title', 'body'), new TrackerTaskState.Ready(), AbortFacts.none())
        if (!designators.byKind().isEmpty()) {
            harness.seedDesignators(ref, designators)
        }
    }

    /** Runs a real take to completion for {@code taskId}, discarding the {@link TakeExitCodeException} every take run ends with — used where only the branch's resulting pin, not this run's own exit code, is under test. */
    private void runToCompletion(String taskId) {
        try {
            newCommand().run(args('take', taskId, "--dir=$projectDir"))
        } catch (TakeExitCodeException ignored) {
            // Expected: every take invocation ends by throwing this to carry its exit code.
        }
    }

    /**
     * Reads {@code task.json} back from the task branch's history in {@code projectDir}, not from
     * the branch tip or the worktree filesystem: once a task reaches a terminal Completed outcome,
     * {@link com.github.oinsio.gnomish.adapter.git.GitTaskRepository}'s cleanup commit removes
     * {@code .gnomish-task/} from the tip, and the pipeline also removes the worktree. The base pin
     * (FR7) is written once, at task creation, and never rewritten afterward, so the earliest
     * commit in the branch's history that carries the path — its creation commit — is read instead.
     */
    private TaskRecord readTaskRecord(String taskId) {
        String branch = "gnomish/${taskId}"
        def commits = gitOutput(projectDir, 'log', '--format=%H', branch, '--', '.gnomish-task/task.json').split('\n')
        String creationCommit = commits[commits.length - 1]
        def json = gitOutput(projectDir, 'show', "${creationCommit}:.gnomish-task/task.json")
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(json))
    }

    // M1: on a zero-config project, a newly created task branch's recorded base SHA equals the
    // remote default-branch tip observed at claim, and the rule names the zero-config tier.
    def "zero-config claim pins the task branch base to the remote default-branch tip"() {
        given: 'no task-branch section at all, and a task naming no base designator'
        writeConfig()
        def ref = new TaskRef('PROJ-1')
        seedReady(ref)
        def remoteTip = gitOutput(originDir, 'rev-parse', 'HEAD')

        when:
        newCommand().run(args('take', 'PROJ-1', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 0

        and: 'the pin came from the remote tip observed at claim, under the zero-config tier'
        def record = readTaskRecord('PROJ-1')
        record.baseCommit() == remoteTip
        record.baseRule() == BaseRule.REPOSITORY_DEFAULT_BRANCH
    }

    // M1: staleness does not grow with serve uptime — a second, later claim against an advanced
    // remote pins the NEW tip, not the tip observed by the first claim's process.
    def "a later claim against an advanced remote pins the new tip, not a stale one"() {
        given: 'a first claim taken against the remote as it stood originally'
        writeConfig()
        def firstRef = new TaskRef('PROJ-2')
        seedReady(firstRef)
        def firstTip = gitOutput(originDir, 'rev-parse', 'HEAD')
        runToCompletion('PROJ-2')
        def firstRecord = readTaskRecord('PROJ-2')

        and: 'the remote default branch advances with a new commit'
        commit(projectDir, 'advance.txt', 'advance the remote default branch\n')
        pushOrigin(projectDir)
        def advancedTip = gitOutput(originDir, 'rev-parse', 'HEAD')

        and: 'a second task is claimed afterward'
        def secondRef = new TaskRef('PROJ-3')
        seedReady(secondRef)

        when:
        newCommand().run(args('take', 'PROJ-3', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 0

        and: 'the first claim pinned the original tip, the second pinned the advanced one — not the first, stale tip'
        firstRecord.baseCommit() == firstTip
        advancedTip != firstTip
        def secondRecord = readTaskRecord('PROJ-3')
        secondRecord.baseCommit() == advancedTip
    }

    // U2: a triager-selected release base is fetched, validated against the allowed bases, and
    // pinned — the happy path only; the disallowed-selection park/report case is out of scope here
    // (covered at unit level by FreshClaimBaseBindingSpec's DESIGNATOR_NOT_ALLOWED case).
    def "a designator-selected release base is fetched, validated, and pinned"() {
        given: 'an allowed-bases section naming a release pattern'
        writeConfig('''\
task-branch:
  base:
    allowed:
      - pattern: "release/*"
        role: release
''')

        and: 'a release/1.18 branch on the remote with its own distinct tip commit'
        def mainBranch = gitOutput(projectDir, 'symbolic-ref', '--short', 'HEAD')
        gitOutput(projectDir, 'checkout', '-b', 'release/1.18')
        commit(projectDir, 'release-note.txt', 'release 1.18 content\n')
        def releaseTip = gitOutput(projectDir, 'rev-parse', 'HEAD')
        gitOutput(projectDir, 'push', 'origin', 'release/1.18:refs/heads/release/1.18')
        gitOutput(projectDir, 'checkout', mainBranch)

        and: 'the task names release/1.18 via a seeded base designator'
        def ref = new TaskRef('PROJ-4')
        seedReady(ref, TaskDesignators.of('base', Designator.single('release/1.18')))

        when:
        newCommand().run(args('take', 'PROJ-4', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 0

        and: 'the pin names release/1.18, its real remote tip, and the DESIGNATOR rule'
        def record = readTaskRecord('PROJ-4')
        record.baseRef() == 'release/1.18'
        record.baseCommit() == releaseTip
        record.baseRule() == BaseRule.DESIGNATOR
    }
}
