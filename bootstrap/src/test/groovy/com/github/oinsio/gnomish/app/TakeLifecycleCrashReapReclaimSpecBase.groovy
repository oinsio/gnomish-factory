package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.adapter.git.WorktreeSalvage
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeExitCodeMapper
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The crash-reap-reclaim lifecycle on the real medium (FR6, NFR-R1 of fix-claim-epoch-fence), the
 * spec scenario "Crashed, reaped, reclaimed": instance A dies mid-round leaving a salvaged tip
 * stamped with its epoch, the claim is reaped, instance B claims and resumes the branch at the
 * recorded position, and a second pickup of the same branch changes nothing.
 *
 * <p>Instance A is modelled at the ports rather than as a second {@link TakeCommand}, deliberately:
 * a factory process cannot be killed from inside its own JVM, and a take that hangs mid-round and
 * is then abandoned would make the spec depend on thread and subprocess timing. So A's tenure is
 * driven exactly as A itself would drive it — the REAL tracker issues the epoch through {@link
 * Tracker#claim}, and the REAL git adapters write A's branch from a book holding that epoch, so the
 * round and salvage commits carry A's stamp because the production writers put it there. The half
 * under test is B's: a fully assembled {@link TakeCommand} from {@link TwoInstanceTakeFixture},
 * which claims, classifies the stamped tip, and resumes — the path that quarantined every reclaim.
 *
 * <p>The reap is the reaper's own port operation ({@link Tracker#removeStaleClaim}) over the facts
 * a reader observes through {@link Tracker#listOpen}, not a human return: what returns the task is
 * the stale-claim removal, exactly as {@code Reaper} performs it.
 *
 * <p>Base/subclass split as in {@link TakeLifecycleEscalateResumeSpecBase}: a concrete adapter's
 * seeding and thread reading name a concrete adapter class, which {@code TrackerPortBoundarySpec}
 * (FR1) allows only inside {@code adapter.tracker}, so they are seams a subclass fills in.
 *
 * <p>Implements FR6, NFR-R1 of fix-claim-epoch-fence.
 */
abstract class TakeLifecycleCrashReapReclaimSpecBase extends Specification implements TwoInstanceTakeFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')
    protected static final String TASK_ID = 'PROJ-1'
    protected static final String TASK_BRANCH = 'gnomish/PROJ-1'

    @TempDir
    Path tempDir

    private final GitProcessRunner gitRunner = new GitProcessRunner()

    /** The epochs this spec's tracker issued, in claim order — A's, then B's. */
    protected ClaimWatchingTrackerFactory claimWatcher

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@link #REF} */
    abstract List seededReadyTrackerAndFactory(TaskRef ref, String title, String body)

    /** @return {@code tracker}'s correspondence thread on {@code ref} as {@code "KIND: text"} lines, oldest first */
    abstract List<String> thread(Tracker tracker, TaskRef ref)

    /**
     * Reopens {@code ref} into a fresh {@code Ready} state — models a delivered task whose tracker
     * finish never landed, so the SAME branch is picked up a second time.
     */
    abstract void reopenAsReady(TaskRef ref, String title, String body)

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        claimWatcher = new ClaimWatchingTrackerFactory(seeded[1] as TrackerAdapterFactory)
        trackerFactory = claimWatcher
        writeTwoInstanceProjectFixture()
        relaxTheStage()
    }

    def "crashed mid-round -> salvaged tip -> reaped -> reclaimed by a different instance -> delivered"() {
        given: 'instance A claims the task for real: the tracker issues its tenure epoch'
        def claimedByA = tracker.claim(REF, 'instance-a')
        def epochA = (claimedByA as ClaimResult.Acquired).epoch()

        and: 'A writes its branch under that tenure, records one round, then dies mid-round leaving uncommitted work'
        crashInstanceA(epochA)

        expect: 'the tip A left behind is a salvage commit carrying A\'s epoch, and the round it recorded is durable'
        subjectOf(TASK_BRANCH) == 'gnomish: salvage'
        stampOf(TASK_BRANCH) == epochA

        when: 'the reaper returns the dead claim to circulation — the port operation, over observed facts'
        reapClaim()

        then: 'the task is Ready again, naming instance-a in the stale-claim marker'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Ready
        thread(tracker, REF).any {
            it.startsWith('STALE_CLAIM_REMOVED') && it.contains('instance-a')
        }

        when: 'instance B — a freshly assembled TakeCommand sharing nothing in-process with A — takes the same ref'
        def tipBeforeReclaim = gitOutput(projectDir, 'rev-parse', TASK_BRANCH)
        def instanceB = newCommand('instance-b')
        instanceB.run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'B resumed A\'s branch and delivered the task (exit code 0)'
        def secondRun = thrown(TakeExitCodeException)
        secondRun.exitCode() == 0
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'B resumed at the recorded position rather than starting the task over'
        def reclaimCommits = commitsSince(tipBeforeReclaim)
        !reclaimCommits.isEmpty()
        reclaimCommits.every {
            !subjectOf(it).startsWith('gnomish: task started')
        }

        and: 'every commit of B\'s tenure carries the epoch the tracker issued B (FR3, FR6)'
        // A claimed through the port directly, modelling a process this spec never assembles, so
        // the watcher — which sits on the factory the COMMANDS resolve through — saw only B's claim.
        def epochB = claimWatcher.issuedEpochs[0]
        epochB != epochA
        reclaimCommits.every { stampOf(it) == epochB }

        when: 'the same branch is picked up a second time — the task is put back to Ready under it'
        def tipAfterDelivery = gitOutput(projectDir, 'rev-parse', TASK_BRANCH)
        reopenAsReady(REF, 'Add widgets', 'please add widgets')
        def threadBeforeSecondPickup = thread(tracker, REF)
        def instanceC = newCommand('instance-c')
        instanceC.run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'C declines it without claiming — the branch already says the work is done (the reopened-finished refusal)'
        def thirdRun = thrown(TakeExitCodeException)
        thirdRun.exitCode() == TakeExitCodeMapper.exitCodeFor(new TakeResult.Skipped('declined'))

        and: 'the second pickup changed nothing: the branch is untouched, the task is Finished again, no round ran (NFR-R1)'
        gitOutput(projectDir, 'rev-parse', TASK_BRANCH) == tipAfterDelivery
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished
        // Everything the second pickup itself added — B's own PROGRESS entry stays on the thread,
        // and asserting over the whole correspondence would test B's run a second time.
        thread(tracker, REF).drop(threadBeforeSecondPickup.size()).every {
            !it.startsWith('PROGRESS:')
        }
    }

    /**
     * Instance A's whole tenure, written by the production adapters over a book holding the epoch
     * the tracker issued A: the task branch, one durably recorded round, the gnome's interrupted
     * work left uncommitted, and the salvage commit that turns those leftovers into the tip.
     */
    private void crashInstanceA(ClaimEpoch epochA) {
        def book = new ClaimEpochBook()
        book.issued(TASK_ID, epochA)
        def repository = new GitTaskRepository(gitRunner, projectDir, worktreesRoot, book)
        // The base A pins is the clone's real branch name, as a fresh take's resolution would pin
        // it — not the literal 'HEAD', which origin holds no ref by, so B's resume could never
        // resolve it (FR13, D15 of add-base-ref-resolution).
        def base = currentBranch(projectDir)
        repository.createTask(
                new TaskContext(TASK_ID, 'Add widgets', 'please add widgets', List.<Decision> of()),
                TaskStart.commit(projectDir, base),
                TaskStart.pin(base, BaseRule.REPOSITORY_DEFAULT_BRANCH),
                TaskState.atStageStart('build'))
        def worktree = worktreesRoot.resolve('project').resolve(TASK_ID)
        new GitAttemptPersistence(gitRunner, worktree, TASK_ID, book).persist(
                TASK_ID,
                TaskState.atStageStart('build'),
                new ToolTrace(
                        new AttemptKey(TASK_ID, 'build', 0),
                        [
                            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                        ]))
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work\n')
        new WorktreeSalvage(gitRunner, worktree, book).salvage(TASK_ID)
    }

    /** The reaper's own operation: remove the claim the reader observed, returning the task to Ready. */
    private void reapClaim() {
        def observed = tracker.listOpen().find { it.ref() == REF }
        assert observed != null: 'the crashed task is not in the open feed, so no reap is possible'
        tracker.removeStaleClaim(REF, observed.facts().claim())
    }

    /**
     * Drops the shared fixture's deliberately-failing {@code files_exist} check and its
     * attempt limit of one: this scenario is about resuming a crashed tenure, so B's round must be
     * able to pass. Rewritten before any task branch exists, so the branch's law commit — pinned at
     * creation — is this one.
     */
    private void relaxTheStage() {
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/build/instructions.md
advancement: auto
''')
        commitAll(projectDir, 'relax the build stage')
        pushOrigin(projectDir)
    }

    /** The claim epoch stamped on {@code rev}'s commit message, read straight out of {@code git log}. */
    protected ClaimEpoch stampOf(String rev) {
        def matcher = gitOutput(projectDir, 'log', '-1', '--format=%B', rev) =~ /(?m)^Gnomish-Claim-Epoch: (\d+)$/
        matcher ? new ClaimEpoch(Long.parseLong(matcher[0][1] as String)) : null
    }

    /** {@code rev}'s commit subject — the service message, without the epoch trailer below it. */
    protected String subjectOf(String rev) {
        gitOutput(projectDir, 'log', '-1', '--format=%s', rev).strip()
    }

    /** The commits {@code exclusiveFrom} does not already carry, oldest first — one tenure's work. */
    protected List<String> commitsSince(String exclusiveFrom) {
        gitOutput(projectDir, 'log', '--reverse', '--format=%H', "${exclusiveFrom}..${TASK_BRANCH}")
                .readLines()
                .findAll { !it.isBlank() }
    }
}
