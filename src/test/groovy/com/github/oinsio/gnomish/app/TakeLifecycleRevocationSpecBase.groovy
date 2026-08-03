package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The revocation lifecycle end to end (task 6.3 of add-tracker-port, FR15): ready -> claim ->
 * work -> a human closes the task mid-run -> the NEXT round-boundary check detects revocation ->
 * salvage-commit, best-effort push, structural "work stopped" note, release claim — tracker state
 * left exactly as the human's close set it ({@code Gone}), branch and worktree kept.
 *
 * <p>Mirrors the {@link TakeLifecycleReadyToDeliveredSpecBase}/{@link
 * TakeLifecycleEscalateResumeSpecBase} base/subclass split: {@code TrackerPortBoundarySpec} (FR1)
 * forbids any class outside {@code adapter.tracker} from depending on a concrete adapter class,
 * and {@link ManualRunAssembly}/{@link TakeCommand} are package-private, so this base lives in
 * {@code app} and only ever touches the tracker through the {@link Tracker}/{@link
 * TrackerAdapterFactory} port types — {@link #seededReadyTrackerAndFactory}, {@link #thread}, and
 * {@link #closeOnSecondFetch} are the seams a subclass fills in with a concrete adapter.
 *
 * <p>The pipeline is TWO {@code agent-cli} {@code AUTO} stages with no {@code verify} checks
 * (trivially passing), backed by the fake agent's {@code plain-round} scenario (the 6.1/6.2
 * technique). This gives {@link com.github.oinsio.gnomish.app.take.RevocationCheckingAttemptPersistence}
 * two round boundaries to check inside ONE {@code command.run()} call: round one's check passes
 * (still {@code Working} under this instance), then round two's check runs. {@link
 * #closeOnSecondFetch} arms the tracker so that SECOND {@code fetchTask} call — round two's
 * boundary check — closes the task (a human closing the issue mid-run) before answering, and
 * writes a leftover file into the worktree first: since {@code GitAttemptPersistence#persist}
 * always commits before running the tracker check, anything written during the check itself is
 * genuinely uncommitted at the moment revocation is discovered — the "salvage uncommitted work"
 * scenario FR15 describes; written any earlier, it would just be swept into that round's commit.
 *
 * <p>Implements FR15 of add-tracker-port.
 */
abstract class TakeLifecycleRevocationSpecBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')
    protected static final String LEFTOVER_FILE = 'leftover-uncommitted.txt'

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Path bareRepo
    Tracker tracker
    TrackerAdapterFactory trackerFactory

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@link #REF} */
    abstract List seededReadyTrackerAndFactory(TaskRef ref, String title, String body)

    /** @return {@code tracker}'s correspondence thread on {@code ref} as {@code "KIND: text"} lines, oldest first */
    abstract List<String> thread(Tracker tracker, TaskRef ref)

    /**
     * Arms {@code tracker} so that its SECOND {@code fetchTask(ref)} call — the second round's
     * "still ours and alive" boundary check — closes {@code ref} (simulating a human closing the
     * tracker task mid-run) before returning, having first written {@code leftoverFile} into the
     * worktree so it is genuinely uncommitted at the moment revocation is discovered. The first
     * {@code fetchTask} call (the first round's own boundary check) is left untouched and must
     * still report the task {@code Working} under this run's own claim.
     */
    abstract void closeOnSecondFetch(TaskRef ref, Path leftoverFile)

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory
        writeTwoStageProjectFixture()
    }

    /** Writes a two-AUTO-stage pipeline: both stages pass trivially (no verify checks). */
    private void writeTwoStageProjectFixture() {
        projectDir = initWorkingRepo(tempDir, 'project')
        ['first', 'second'].each { stageName ->
            Files.createDirectories(projectDir.resolve(".gnomish/stages/${stageName}"))
            Files.createDirectories(projectDir.resolve("stages/${stageName}"))
            // Written at both paths: the pipeline loader resolves `instructions:` relative to the
            // .gnomish/ root, while the runtime engine resolves the same string relative to the
            // workspace root (the task worktree) — see TakeCommandCredentialScrubSpec's own note.
            Files.writeString(projectDir.resolve(".gnomish/stages/${stageName}/instructions.md"), 'do it\n')
            Files.writeString(projectDir.resolve("stages/${stageName}/instructions.md"), 'do it\n')
            Files.writeString(projectDir.resolve(".gnomish/stages/${stageName}/stage.yaml"), """\
purpose: ${stageName} stage
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/${stageName}/instructions.md
advancement: auto
""")
        }
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - first\n  - second\n')
        // tracker.type is 'github' purely to satisfy TrackerSeamValidator's registered-type check
        // (this fixture passes TrackerValidatorStub.acceptingGithub() as the validator registry, so
        // 'github' is a known-but-permissive type — content isn't under test here) — this
        // fixture's own trackerAdapterRegistry below is independent of that seam and is what
        // actually resolves the live Tracker, so this fixture registers the real tracker under the
        // SAME key, 'github', overriding which adapter backs that type for this run.
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
        // A real origin remote so BranchPush actually pushes (with none configured, push is a
        // silent no-op, per BranchPush's own contract) — every task worktree created via `git
        // worktree add` under projectDir inherits this remote from the shared .git.
        bareRepo = initBareRepo(tempDir, 'origin.git')
        addRemote(projectDir, 'origin', bareRepo.toString())
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private TakeCommand newCommand(FactoryProperties factoryProperties) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: trackerFactory],
                TrackerValidatorStub.acceptingGithub())
    }

    def "ready -> claim -> work -> human closes mid-run -> revoked: salvage, push, note, release; branch and worktree kept"() {
        given: 'a Ready task seeded directly in a real tracker, and a two-stage fake-agent-backed pipeline'
        def factoryProperties = FakeAgentSupport.propertiesFor('plain-round')
        def command = newCommand(factoryProperties)
        Path worktree = worktreesRoot.resolve('project').resolve('PROJ-1')

        and: 'the tracker is armed to close the task on the second round-boundary check, dropping a leftover file'
        closeOnSecondFetch(REF, worktree.resolve(LEFTOVER_FILE))

        when: 'take is run against the seeded ref in explicit mode'
        command.run(args('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the run reaches the Revoked exit code (14), per design D16'
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 14

        and: 'the tracker state is left exactly as the human close set it (FR15): Gone, untouched by the factory'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Gone

        and: 'the branch still exists in the bare remote, carrying the salvage commit (kept, per FR15)'
        def bareBranchTip = gitOutput(bareRepo, 'rev-parse', 'gnomish/PROJ-1')
        bareBranchTip.length() == 40

        and: 'the salvage commit is present in the branch history, carrying the leftover file'
        gitOutput(bareRepo, 'log', '-1', '--format=%s', 'gnomish/PROJ-1') == 'gnomish: salvage'
        gitOutput(bareRepo, 'show', '--stat', 'gnomish/PROJ-1').contains(LEFTOVER_FILE)

        and: 'the worktree is kept on disk, not deleted'
        Files.exists(worktree)

        and: 'the tracker thread tells the story: claim, the first round durable-progress marker (FR2 of fix-abort-progress-reset), then the structural stop note (UX4)'
        def entries = thread(tracker, REF)
        entries.size() == 3
        entries[0].startsWith('CLAIM:')
        entries[1].startsWith('PROGRESS:')
        def noteEntry = entries[2]
        noteEntry.startsWith('NOTE:')
        noteEntry.contains('Work stopped')
    }
}
