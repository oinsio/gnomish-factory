package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
import java.time.Duration
import java.util.Random
import spock.lang.Specification

/**
 * FR10, NFR-C1 of add-tracker-port and FR6, D2, D5 of add-factory-serve: bare mode's claim walk.
 * Given the feed's ready queue it picks claim candidates, walks them in order, and stops at the
 * FIRST one it actually claims. Three distinct "nothing taken" endings have to stay distinguishable
 * to an operator, because they mean different things: a structurally empty queue, a queue held back
 * by the WIP limit, and a queue every entry of which was claimed by someone else mid-walk.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules): the tracker is scripted and
 * the claim chain's own git ports refuse every claim, so the walk's decisions are observable
 * without a working copy.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class BareTakeClaimWalkSpec extends Specification implements RunChainFakes {

    private static final Duration BACKOFF_BASE = Duration.ofMinutes(1)
    private static final Duration BACKOFF_CAP = Duration.ofHours(1)

    private Tracker tracker = Mock(Tracker)

    def setup() {
        // The per-candidate open-front re-check (design D5) reads the live queue on every candidate;
        // scenarios that are not about that gate keep it empty so it never blocks.
        tracker.listOpen() >> []
    }

    private static ReadyTask ready(String id, boolean returned = false) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), returned, false, 'title')
    }

    /** A walk over a claim chain whose git ports are never reached in these scenarios. */
    private BareTakeClaimWalk walk(int wipLimit = 10) {
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
        }, Stub(TaskWorktreeGit))
        new BareTakeClaimWalk(claimAndWork(git, tracker, Stub(RunAssembly)), 'taskId',
                BACKOFF_BASE, BACKOFF_CAP, FIXED_CLOCK, wipLimit, new Random(1))
    }

    private TakeResult resolve(BareTakeClaimWalk subject, List<ReadyTask> readyTasks, int openFrontCount = 0) {
        subject.resolve(CLONE_DIR, pipeline(), RunArguments.InteractiveMode.NONE, tracker, INSTANCE,
                readyTasks, openFrontCount)
    }

    // FR10, D2: an empty feed is an EmptyQueue — the daemon's "nothing to do, idle" signal, which
    // must not be confused with any of the blocked cases below.
    def "reports an empty queue when the feed has nothing at all"() {
        expect:
        resolve(walk(), []) instanceof TakeResult.EmptyQueue
    }

    // FR6, D2: tasks exist and are backoff-eligible, but the open front is at the WIP limit, so none
    // may be STARTED. That is a deliberate hold, not an empty queue, and the message names both the
    // limit and how many tasks are waiting on it.
    def "reports the WIP limit, not an empty queue, when fresh tasks are held back by it"() {
        when:
        def result = resolve(walk(1), [
            ready('github:o/r#1'),
            ready('github:o/r#2')
        ], 1)

        then:
        result instanceof TakeResult.Skipped
        result.reason().contains('WIP limit reached: 1')
        result.reason().contains('2 fresh task(s) waiting')

        and: 'nothing was claimed'
        0 * tracker.claim(_, _)
    }

    // FR10, D5: the walk stops at the FIRST candidate it wins — a bare run takes one task per
    // invocation, so a second claim attempt after a successful one would be a second task started.
    // (The candidate ORDER is FeedPolicy's business, deliberately randomized, so this asserts the
    // count and the dispatched ref rather than which entry won.)
    def "stops at the first candidate it wins and dispatches that one"() {
        given: 'the claim chain is stopped at its first git call, so the dispatch is observable'
        def store = Stub(TaskStoreGit) {
            taskRepository(_, _) >> {
                throw new UsageException('dispatched')
            }
        }
        def git = new TaskGit(store, Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
        }, Stub(TaskWorktreeGit))
        def subject = new BareTakeClaimWalk(claimAndWork(git, tracker, Stub(RunAssembly)), 'taskId',
                BACKOFF_BASE, BACKOFF_CAP, FIXED_CLOCK, 10, new Random(1))

        when:
        resolve(subject, [
            ready('github:o/r#1'),
            ready('github:o/r#2')
        ])

        then: 'exactly one claim was won, and only that task was dispatched'
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired()
        1 * tracker.fetchTask(_) >> readyTask()
        thrown(UsageException)
    }

    // FR10: losing a claim race is not the end of the walk — another instance winning one entry says
    // nothing about the next, so the walk tries the following candidate rather than giving up.
    def "falls through to the next candidate after losing a claim race"() {
        given:
        def store = Stub(TaskStoreGit) {
            taskRepository(_, _) >> {
                throw new UsageException('dispatched')
            }
        }
        def git = new TaskGit(store, Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
        }, Stub(TaskWorktreeGit))
        def subject = new BareTakeClaimWalk(claimAndWork(git, tracker, Stub(RunAssembly)), 'taskId',
                BACKOFF_BASE, BACKOFF_CAP, FIXED_CLOCK, 10, new Random(1))

        when:
        resolve(subject, [
            ready('github:o/r#1'),
            ready('github:o/r#2')
        ])

        then: 'the lost race did not stop the walk — the next candidate was claimed and dispatched'
        2 * tracker.claim(_, _) >>> [
            new ClaimResult.Held('someone-else'),
            new ClaimResult.Acquired()
        ]
        1 * tracker.fetchTask(_) >> readyTask()
        thrown(UsageException)
    }

    // FR10, D2: every candidate lost its race. That is a third distinct ending — the queue was not
    // empty and not WIP-blocked, the work simply went to other instances this run.
    def "reports the claim race when every candidate was taken by someone else"() {
        when:
        def result = resolve(walk(), [
            ready('github:o/r#1'),
            ready('github:o/r#2')
        ])

        then:
        2 * tracker.claim(_, _) >> new ClaimResult.Held('someone-else')
        result instanceof TakeResult.Skipped
        result.reason().contains('already claimed by another instance')
    }

    // D5: the open-front count is re-checked per candidate, because it can grow between the feed
    // snapshot and this claim attempt. A FRESH candidate that would overshoot the limit is skipped
    // — and skipping it is not a claim-race loss, so the walk simply moves on.
    def "skips a fresh candidate whose open front grew past the limit since the snapshot"() {
        given:
        def openNow = [
            new OpenTask(REF, new TrackerTaskState.Working('other'), null, 'title')
        ]

        when:
        def result = resolve(walk(1), [
            ready('github:o/r#1', true),
            ready('github:o/r#2')
        ], 0)

        then: 'the returned task is claimed; the fresh one is gated out by the re-check'
        tracker.listOpen() >> openNow
        1 * tracker.claim(new TaskRef('github:o/r#1'), _) >> new ClaimResult.Held('someone-else')
        0 * tracker.claim(new TaskRef('github:o/r#2'), _)

        and:
        result instanceof TakeResult.Skipped
    }

    // FR10: the walk hands back the claimed task's OWN result — it does not translate or swallow it.
    // Driven here through the crash-abort ending, which is a returned result rather than a thrown
    // exception, so the value really travels back out of the walk.
    def "returns the claimed task's own result"() {
        given: 'the dispatched run crashes, which the claim chain turns into an abort RESULT'
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit) {
            locate(_, _) >> {
                throw new IllegalStateException('the runner blew up')
            }
        }, Stub(TaskWorktreeGit))
        def subject = new BareTakeClaimWalk(claimAndWork(git, tracker, Stub(RunAssembly)), 'taskId',
                BACKOFF_BASE, BACKOFF_CAP, FIXED_CLOCK, 10, new Random(1))

        when:
        def result = resolve(subject, [ready('github:o/r#1')])

        then:
        1 * tracker.claim(_, _) >> new ClaimResult.Acquired()
        1 * tracker.recordAbort(_, _)

        and: 'fetched twice: once to dispatch, once by the crash-abort reading the abort facts'
        2 * tracker.fetchTask(_) >> readyTask()

        and:
        result instanceof TakeResult.Aborted
    }
}
