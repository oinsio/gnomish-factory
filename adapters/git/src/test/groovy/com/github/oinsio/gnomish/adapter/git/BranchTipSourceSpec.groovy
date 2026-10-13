package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.branch.TipRelation
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR5 of harden-task-branch-contract: the three access paths of the tip-reader seam — a
 * worktree's own {@code HEAD}, a named ref of a clone, and a ref of a bare repository — answer the
 * same questions the same way, and every one of them reads the task's registered commit (FR13 of
 * tighten-spec-contract) rather than the files on disk. Every write here is registered as the
 * closing push would register it.
 */
class BranchTipSourceSpec extends Specification implements BareGitRepoFixture, RegisteredBranchFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    RegisteredClone registeredClone
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        commitAll(cloneDir)
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        repository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
        repository.createTask(new TaskContext('PROJ-1', UntrustedText.tracker('Fix the thing'), UntrustedText.tracker('Body'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        registerTip(cloneDir, 'PROJ-1')
    }

    private Path worktree(String taskId = 'PROJ-1') {
        registeredClone.worktrees().resolve(taskId)
    }

    /**
     * The three access paths the factory reads a tip through, all served by the one production
     * implementation: only the (repository, revision) pair differs. Keeping them enumerated is the
     * point of this spec — a change to {@code git show} handling that breaks the bare-repository
     * path while the clone path stays green is exactly the regression it exists to catch.
     */
    private List<BranchTipSource> allSources(String taskId = 'PROJ-1') {
        [
            RefTipSource.at(runner, worktree(taskId), taskId, 'HEAD'),
            RefTipSource.at(runner, cloneDir, taskId, "gnomish/$taskId"),
            RefTipSource.at(runner, cloneDir.resolve('.git'), taskId, "gnomish/$taskId")
        ]
    }

    /**
     * The same three access paths as {@link #allSources}, as the (repository, revision) pairs a raw
     * {@code git log} reads — used where the subject is the commit text rather than the seam.
     */
    private List<List<Object>> allRevisions(String taskId = 'PROJ-1') {
        [
            [worktree(taskId), 'HEAD'],
            [cloneDir, "gnomish/$taskId"],
            [
                cloneDir.resolve('.git'),
                "gnomish/$taskId"
            ]
        ]
    }

    // FR1: a file present at the tip reads back identically through all three media.
    def "every medium reads a file the tip carries"() {
        expect:
        allSources().every {
            it.readAtRegister('.gnomish-task/task.json').map { text ->
                text.contains('PROJ-1')
            }.orElse(false)
        }
    }

    // FR1: absence is an empty answer, not a failure — the classifier decides what absence means.
    // The absent path is a decision file: the STARTED commit carries task.json AND state.json
    // (FR3), so neither of those is absent on any live branch any more.
    def "every medium reports an absent file as empty"() {
        expect:
        allSources().every {
            it.readAtRegister('.gnomish-task/decisions/implement-a0.json').isEmpty()
        }
    }

    // FR5 of harden-logging-observability: absence and "git refused the read" give the same empty
    // answer, so the DEBUG classification is the only place git's own reason survives (NG1: the
    // behavior itself is unchanged).
    def "FR5: an absent file's empty answer carries git's reason at DEBUG"() {
        given:
        def logs = LogCaptureSupport.attach(GitShowTip, Level.DEBUG)

        when:
        def read = RefTipSource.at(runner, cloneDir, 'PROJ-1', 'gnomish/PROJ-1')
                .readAtRegister('.gnomish-task/decisions/implement-a0.json')
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        read.isEmpty()

        and:
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('reading as absent')
        events[0].formattedMessage.contains('.gnomish-task/decisions/implement-a0.json')
    }

    // FR3: the STARTED commit carries the initial state.json beside task.json, so a tip read
    // right after branch creation already answers the classifier's state question.
    // Scenario: git/git-task-persistence / A first-round crash resumes from the initial state
    def "every medium reads the initial state.json the STARTED commit carries"() {
        expect:
        allSources().every {
            it.readAtRegister('.gnomish-task/state.json').map { text ->
                text.contains('implement')
            }.orElse(false)
        }
    }

    // FR5: the worktree source reads HEAD, never the dirty file beside it — the half-written
    // state.json of a crashed instance must never reach a reader.
    def "the worktree medium reads the tip, not the dirty working copy"() {
        given: 'a worktree whose task.json on disk was overwritten with garbage'
        Files.writeString(worktree().resolve('.gnomish-task').resolve('task.json'), '{ truncated')

        when:
        def read = RefTipSource.at(runner, worktree(), 'PROJ-1', 'HEAD').readAtRegister('.gnomish-task/task.json')

        then:
        read.map { it.contains('PROJ-1') }.orElse(false)
    }

    // FR13: the tenure's epoch rides every commit as a trailer, whichever revision a reader
    //     reaches the tip through. Read as raw commit text (fix-claim-epoch-fence FR3): the stamp
    //     is provenance an operator reads in the log, and the factory parses it back nowhere.
    def "every medium's revision carries the claim epoch stamped on the tip"() {
        given: 'a task branch created by an instance holding the tenure epoch 4711'
        def held = { String id ->
            Optional.of(new ClaimEpoch(4711))
        } as ClaimEpochSource
        new GitTaskRepository(runner, registeredClone, held, new VirtualClock())
                .createTask(new TaskContext('PROJ-2', UntrustedText.tracker('Fix the other thing'), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))

        expect:
        allRevisions('PROJ-2').every { pair ->
            runner.run(pair[0] as Path, 'log', '-1', '--format=%B', pair[1] as String).stdout()
            .contains('Gnomish-Claim-Epoch: 4711')
        }
    }

    // FR13: a tip written with no tenure carries no stamp, and that is a legal answer — such a tip
    //     simply records no provenance.
    def "every medium's revision carries no epoch trailer for an unstamped tip"() {
        expect:
        allRevisions().every { pair ->
            !runner.run(pair[0] as Path, 'log', '-1', '--format=%B', pair[1] as String).stdout()
            .contains('Gnomish-Claim-Epoch')
        }
    }

    // FR1: every medium answers "not delivered" for a live branch, whose registered commit is its
    // STARTED commit — and stands at it.
    def "no medium reports delivery before cleanup"() {
        expect:
        allSources().every {
            !it.registeredCleanup() && it.tipRelation() == new TipRelation.AtRegister()
        }
    }

    // FR13 of tighten-spec-contract: after the cleanup commit each medium reports delivery — the
    // registered commit is the cleanup commit — including after further commits land on top, which
    // stand above the register and are never read.
    /** Completes PROJ-1, cleans its branch up, then lands a human commit on top of the cleanup. */
    private void deliverWithCommitAfterCleanup() {
        def persistence = new GitAttemptPersistence(runner, worktree(), 'PROJ-1', ClaimEpochSource.NONE)
        def trace = new ToolTrace(new AttemptKey('PROJ-1', 'implement', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        persistence.persist('PROJ-1', TaskState.atStageStart('implement'), trace)
        registerTip(cloneDir, 'PROJ-1')
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')), TrackerWrite.OWED)
        registerTip(cloneDir, 'PROJ-1')
        repository.finishCleanup('PROJ-1')
        registerTip(cloneDir, 'PROJ-1')
        Files.writeString(worktree().resolve('note.txt'), 'after cleanup')
        commitAll(worktree(), 'a human commit after cleanup')
    }

    // Scenario: lifecycle/task-branch-contract / A cleanup commit in the task's own history is Delivered
    def "every medium reports delivery at the registered cleanup commit, even under later commits"() {
        given: 'a completed task whose branch gained a human commit after cleanup'
        deliverWithCommitAfterCleanup()

        expect:
        allSources().every {
            it.registeredCleanup() && it.tipRelation() == new TipRelation.AboveRegister()
        }
    }

    // FR4 of fix-envelope-medium; FR13 of tighten-spec-contract (design D18): the writer base's
    //     tree-entry predicate answers for the state directory and for a file inside it at the
    //     registered commit, and a path the register does not carry is a plain false — the cleanup
    //     and salvage guards decide what that absence means.
    def "FR4: the writer base reports the paths the registered commit carries, and none it does not"() {
        expect:
        RegisterBase.carries(runner, worktree(), 'PROJ-1', '.gnomish-task')
        RegisterBase.carries(runner, worktree(), 'PROJ-1', '.gnomish-task/task.json')
        !RegisterBase.carries(runner, worktree(), 'PROJ-1', '.gnomish-task/decisions/implement-a0.json')
    }

    // FR13 of tighten-spec-contract (design D18): the base is the register, not a HEAD the gnome
    //     committed above it — a path only the gnome's commit carries is not the factory's.
    def "FR13: the writer base reads the register, not a gnome commit above it"() {
        given:
        Files.createDirectories(worktree().resolve('.gnomish-forged'))
        Files.writeString(worktree().resolve('.gnomish-forged').resolve('note.txt'), 'gnome')
        commitAll(worktree(), 'gnome commit')

        expect:
        !RegisterBase.carries(runner, worktree(), 'PROJ-1', '.gnomish-forged')
        RegisterBase.show(runner, worktree(), 'PROJ-1', '.gnomish-forged/note.txt').exitCode() != 0
    }

    // FR4 of fix-envelope-medium: the predicate reads a commit, not the working copy — a directory
    //     lying on disk that no commit carries must not read as carried.
    def "FR4: the writer base's predicate reads the register, not the dirty working copy"() {
        given: 'an untracked factory-shaped directory beside the tip'
        Files.createDirectories(worktree().resolve('.gnomish-scratch'))
        Files.writeString(worktree().resolve('.gnomish-scratch').resolve('note.txt'), 'uncommitted')

        expect:
        !RegisterBase.carries(runner, worktree(), 'PROJ-1', '.gnomish-scratch')
    }
}
