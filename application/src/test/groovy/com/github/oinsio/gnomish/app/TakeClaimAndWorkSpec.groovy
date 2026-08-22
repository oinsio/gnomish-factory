package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, FR10, FR14 (design D3, D16) of add-tracker-port and FR1 of add-claim-heartbeat: the claim
 * choke point every take path goes through. Four facts belong to it and nowhere else — a lost
 * claim race refuses naming the holder; a held claim routes on whether the task already has a
 * branch (fresh claim vs resume); the instance heartbeat is registered the instant a claim is held
 * and unregistered however the run ends; and an uncaught crash of a CLAIMED run funnels into the
 * abort protocol while deliberate exit-code control flow is rethrown unchanged.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules): the tracker and the three
 * git ports are scripted, and each scenario stops the routed-to path at its first port call, which
 * is what makes "which way did it route" observable without a working copy or a real engine run.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class TakeClaimAndWorkSpec extends Specification implements RunChainFakes {

    @TempDir
    Path tempDir

    Path worktreesRoot

    private Path worktree = Path.of('/tmp/gnomish-worktrees/PROJ-1')

    def setup() {
        worktreesRoot = tempDir.resolve('worktrees')
        // DirectoryWorkspace refuses a path that is not an existing directory, so the worktree a
        // completing run resolves to is materialized here — standing in for `git worktree add`.
        Files.createDirectories(TaskWorktreePath.resolve(worktreesRoot, CLONE_DIR, 'PROJ-1'))
    }

    /** Records the register/unregister the heartbeat lifecycle is anchored on. */
    private static class RecordingBeat implements ClaimBeat {
        List<String> events = []

        @Override
        void register(TaskRef ref) {
            events << "register:${ref.id()}"
        }

        @Override
        void unregister(TaskRef ref) {
            events << "unregister:${ref.id()}"
        }
    }

    private TakeResult claim(TakeClaimAndWork subject, Tracker tracker) {
        subject.claimAndWork(CLONE_DIR, null, pipeline(), RunArguments.InteractiveMode.NONE, false,
                readyTask(), tracker, INSTANCE)
    }

    /** The same call, but on a pipeline the scripted engine really runs to completion. */
    private TakeResult claimAndRun(TakeClaimAndWork subject, Tracker tracker) {
        subject.claimAndWork(CLONE_DIR, null, completingPipeline(), RunArguments.InteractiveMode.NONE, false,
                readyTask(), tracker, INSTANCE)
    }

    // FR9, UX2: losing the claim race is a refusal, not a failure — it names the holder and stops
    // before anything touches git, because a task we do not hold is not ours to work on.
    def "refuses a lost claim race, naming the holder, without reaching git"() {
        given:
        def branches = Mock(TaskBranchGit)
        def tracker = Stub(Tracker) {
            claim(_, _) >> new ClaimResult.Held('gnomish-other-99xxyy')
        }
        def beat = new RecordingBeat()

        when:
        def result = claim(claimAndWork(new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit)),
                tracker, Stub(RunAssembly), beat), tracker)

        then:
        result instanceof TakeResult.Skipped
        result.reason().contains('gnomish-other-99xxyy')

        and: 'no branch was ever located — the routing never happened'
        0 * branches._

        and: 'and no claim was held, so the heartbeat never beat for this ref (FR1)'
        beat.events.isEmpty()
    }

    // FR9, D3: a held claim with NO branch for this taskId is a FRESH claim — the branch/worktree
    // are created for the first time. Stopped here at createTask, whose failure on a fresh run is
    // remapped to a usage error (FR7 of add-git-workflow), which is what identifies the route.
    def "routes a held claim with no branch to the fresh-claim path"() {
        given:
        def tracker = Stub(Tracker) {
            claim(_, _) >> new ClaimResult.Acquired()
        }
        def store = Stub(TaskStoreGit) {
            taskRepository(_, _) >> Stub(TaskLifecycleStore) {
                createTask(_, _) >> {
                    throw new GitTaskRepositoryException('PROJ-1', TaskLifecycleEvent.STARTED, 'branch exists', 'x')
                }
            }
        }
        def branches = Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
        }

        when:
        claim(claimAndWork(new TaskGit(store, branches, Stub(TaskWorktreeGit)), tracker, Stub(RunAssembly)), tracker)

        then: 'the fresh-claim path ran and its own usage error propagates unchanged (D16: exit 2 is kept)'
        def ex = thrown(UsageException)
        ex.message.startsWith('could not start git-mode task')
    }

    // FR9, D3: a held claim with an EXISTING branch is a resume — the disposition-resume chain
    // bootstraps the branch (harden, locate, materialize the worktree, reconcile) instead of
    // creating anything. Stopped at the task.json read, the bootstrap's last step.
    def "routes a held claim with an existing branch to the resume path"() {
        given:
        def tracker = Stub(Tracker) {
            claim(_, _) >> new ClaimResult.Acquired()
        }
        def branches = Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        }
        def worktrees = Mock(TaskWorktreeGit)
        def store = Stub(TaskStoreGit) {
            readTaskRecord(_) >> {
                throw new UsageException('stopped at the resume bootstrap')
            }
        }

        when:
        claim(claimAndWork(new TaskGit(store, branches, worktrees), tracker, Stub(RunAssembly)), tracker)

        then: 'the existing branch was materialized and reconciled — never created'
        1 * worktrees.ensureWorktree(CLONE_DIR, WORKTREES_ROOT, 'PROJ-1', 'gnomish/PROJ-1') >> worktree
        1 * worktrees.reconcile(worktree, 'PROJ-1', 'gnomish/PROJ-1')

        and:
        def ex = thrown(UsageException)
        ex.message == 'stopped at the resume bootstrap'
    }

    // FR1 of add-claim-heartbeat: the heartbeat is anchored at THIS choke point — registered the
    // instant the claim is held and unregistered in a finally, so it stops at any terminal result,
    // exception or crash-abort. A path that never holds a claim never beats (first scenario above).
    def "registers the heartbeat on a held claim and unregisters it however the run ends"() {
        given:
        def tracker = Stub(Tracker) {
            claim(_, _) >> new ClaimResult.Acquired()
        }
        def branches = Stub(TaskBranchGit) {
            locate(_, _) >> {
                throw new UsageException('stopped right after the claim')
            }
        }
        def beat = new RecordingBeat()

        when:
        claim(claimAndWork(new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit)),
                tracker, Stub(RunAssembly), beat), tracker)

        then:
        thrown(UsageException)
        beat.events == [
            "register:${REF.id()}",
            "unregister:${REF.id()}"
        ]
    }

    // FR14, D16 "Runner crash is an abort": an uncaught RuntimeException of a run whose claim we
    // HOLD is not a bare failure — it runs the best-effort abort protocol against the tracker, so
    // the task never stays stuck Working behind a dead runner.
    def "funnels an uncaught crash of a claimed run into the abort protocol"() {
        given:
        def tracker = Mock(Tracker)
        def branches = Stub(TaskBranchGit) {
            locate(_, _) >> {
                throw new IllegalStateException('the runner blew up')
            }
        }
        def beat = new RecordingBeat()

        when:
        def result = claim(claimAndWork(new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit)),
                tracker, Stub(RunAssembly), beat), tracker)

        then:
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired()
        1 * tracker.recordAbort(REF, _)

        and: 'the crash is reported as an abort result, not rethrown as a bare exception'
        noExceptionThrown()
        result instanceof TakeResult.Aborted

        and: 'and the beat still stopped'
        beat.events.last() == "unregister:${REF.id()}"
    }

    // FR9, D16: a deliberate, dedicated-exit-code failure of a CLAIMED run keeps its exit code —
    // but the claim must not survive it. Nothing else in this process will ever write the tracker
    // for this ref again (the exception exits the invocation), so without a release the task sits
    // Working behind a dead runner until its lease expires — the very outcome the crash-abort arm
    // exists to prevent. Reachable in practice: the sandbox mode selector refuses a container-bound
    // pipeline whose prerequisites are unmet with a UsageException, after the claim is held.
    def "releases the claim when a deliberate #kind aborts a claimed run"() {
        given:
        def tracker = Mock(Tracker)
        def branches = Stub(TaskBranchGit) {
            locate(_, _) >> { throw failure() }
        }

        when:
        claim(claimAndWork(new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit)),
                tracker, Stub(RunAssembly)), tracker)

        then:
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired()
        1 * tracker.release(REF)

        and: 'and the exception still propagates unchanged, keeping its own exit code'
        thrown(expected)

        and: 'the claim is dropped, not aborted or parked — this is not an infrastructure failure'
        0 * tracker.recordAbort(_, _)
        0 * tracker.park(_, _, _)

        where:
        kind | failure | expected
        'usage error' | {
            new UsageException('unmet container prerequisite')
        } | UsageException
        'diverged-branch refusal' | {
            new DivergedBranchException('PROJ-1', 'gnomish/PROJ-1', 'aaa', 'bbb')
        } | DivergedBranchException
    }

    // NFR-R2 in spirit: the release is best-effort. A tracker that is itself the reason the run is
    // bailing out must not replace the operator-facing usage error with a tracker stack trace.
    def "keeps the original failure when the best-effort release itself throws"() {
        given:
        def tracker = Mock(Tracker)
        def branches = Stub(TaskBranchGit) {
            locate(_, _) >> {
                throw new UsageException('unmet container prerequisite')
            }
        }

        when:
        claim(claimAndWork(new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit)),
                tracker, Stub(RunAssembly)), tracker)

        then:
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired()
        1 * tracker.release(REF) >> {
            throw new IllegalStateException('tracker is down')
        }

        and:
        def ex = thrown(UsageException)
        ex.message == 'unmet container prerequisite'

        and: 'the failed release is not lost either — it rides along as suppressed'
        ex.suppressed*.message == ['tracker is down']
    }

    // FR9, D3: the fresh route, followed all the way to its terminal result — the claim choke point
    // hands back what the routed-to path produced, unchanged. Without this, the routing scenarios
    // above only prove which way the run went, not that its result is the one returned.
    def "returns the fresh-claim path's own terminal result"() {
        given:
        def tracker = Mock(Tracker)
        def lifecycleStore = Mock(TaskLifecycleStore)
        def store = Stub(TaskStoreGit) {
            taskRepository(_, _) >> lifecycleStore
            attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
            readTaskRecord(_) >> freshRecord()
        }
        def branches = Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
        }
        def beat = new RecordingBeat()

        when:
        def result = claimAndRun(claimAndWork(new TaskGit(store, branches, Stub(TaskWorktreeGit)),
                tracker, assemblyRunning(new ScriptedExecutor([completedRound()])), beat,
                new ClaimLossFlag(), worktreesRoot), tracker)

        then:
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired()
        tracker.fetchTask(_) >> heldByUs()
        1 * tracker.finish(REF, _)

        and: 'the engine\'s delivery is what the choke point returns'
        result instanceof TakeResult.Delivered

        and: 'and the beat framed the whole run'
        beat.events == [
            "register:${REF.id()}",
            "unregister:${REF.id()}"
        ]
    }

    // FR9, D3: the resume route, likewise followed to its terminal result. The existing branch is
    // bootstrapped — hooks neutralized, worktree materialized, reconciled — and the run resumes
    // from the recorded state instead of creating anything.
    def "returns the resume path's own terminal result, bootstrapping the existing branch first"() {
        given:
        def tracker = Mock(Tracker)
        def lifecycleStore = Mock(TaskLifecycleStore)
        def branches = Mock(TaskBranchGit)
        def worktrees = Mock(TaskWorktreeGit)
        def resumedWorktree = worktreesRoot.resolve('resumed')
        Files.createDirectories(resumedWorktree)
        def store = Stub(TaskStoreGit) {
            taskRepository(_, _) >> lifecycleStore
            attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
            readTaskRecord(_) >> freshRecord()
            readRecordedState(_) >> TaskState.atStageStart('build')
        }

        when:
        def result = claimAndRun(claimAndWork(new TaskGit(store, branches, worktrees), tracker,
                assemblyRunning(new ScriptedExecutor([completedRound()])), ClaimBeat.NONE,
                new ClaimLossFlag(), worktreesRoot), tracker)

        then: 'the branch is located and bootstrapped, never created'
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired()
        1 * branches.harden(CLONE_DIR)
        // Twice by design: once by the routing decision here, once by the resume bootstrap itself.
        2 * branches.locate(CLONE_DIR, 'PROJ-1') >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        1 * worktrees.ensureWorktree(CLONE_DIR, worktreesRoot, 'PROJ-1', 'gnomish/PROJ-1') >> resumedWorktree
        1 * worktrees.reconcile(resumedWorktree, 'PROJ-1', 'gnomish/PROJ-1')
        0 * lifecycleStore.createTask(_, _)

        and: 'the resume checks the working copy for leftovers of the interrupted attempt'
        worktrees.salvage(resumedWorktree) >> Stub(WorktreeSalvager)

        and:
        tracker.fetchTask(_) >> heldByUs()
        1 * tracker.finish(REF, _)
        result instanceof TakeResult.Delivered
    }
}
