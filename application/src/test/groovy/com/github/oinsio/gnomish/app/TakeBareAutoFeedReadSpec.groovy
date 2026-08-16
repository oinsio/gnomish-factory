package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import java.time.Duration
import java.util.Random
import spock.lang.Specification

/**
 * FR10, NFR-C1 of add-tracker-port and FR3, FR4 of enforce-finish-terminality: bare mode's FEED
 * READ — the three tracker reads that happen before any claim is attempted, and their order. The
 * decline sweep sits between the ready read and the open-front read on purpose: a reopened finished
 * task observed in the feed is declined even on a run that then takes nothing at all, so the
 * factory's "finished is terminal" answer does not depend on which task the walk happens to pick.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules). Named for the feed read
 * rather than the class so it does not collide with the composition root's own {@code
 * TakeBareAutoSpec}, which drives the whole bare flow end to end.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class TakeBareAutoFeedReadSpec extends Specification implements RunChainFakes {

    private Tracker tracker = Mock(Tracker)

    private TakeBareAuto bareAuto() {
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
        }, Stub(TaskWorktreeGit))
        new TakeBareAuto(Stub(RunAssembly), git, WORKTREES_ROOT, new AbortHandler(tracker, FIXED_CLOCK),
                3, 'taskId', Duration.ofMinutes(1), Duration.ofHours(1), FIXED_CLOCK, [], 10, new Random(1))
    }

    private TakeResult run() {
        bareAuto().run(CLONE_DIR, pipeline(), RunArguments.InteractiveMode.NONE, tracker, INSTANCE)
    }

    // FR10: the feed is read, the open front is counted, and the walk decides — an empty feed ends
    // as EmptyQueue without any claim being attempted.
    def "reads the feed and the open front, then reports an empty queue when there is nothing ready"() {
        when:
        def result = run()

        then:
        1 * tracker.listReady(_) >> []
        1 * tracker.listOpen() >> []
        0 * tracker.claim(_, _)
        result instanceof TakeResult.EmptyQueue
    }

    // FR3, FR4 of enforce-finish-terminality: a REOPENED finished task in the feed is declined as
    // part of reading the feed — before the walk runs and regardless of what the walk then does. A
    // decline that only happened for the task the walk picked would let a reopened task sit in the
    // queue indefinitely behind other work.
    def "declines every reopened finished task it observes, even on a run that takes nothing"() {
        given:
        def reopened = new ReadyTask(new TaskRef('github:o/r#7'), AbortFacts.none(), false, true, 'title')

        when:
        def result = run()

        then:
        1 * tracker.listReady(_) >> [reopened]
        1 * tracker.declineFinished(reopened.ref(), _)

        and: 'and the run still took nothing — a reopened finished task is never a claim candidate'
        0 * tracker.claim(_, _)
        tracker.listOpen() >> []
        result instanceof TakeResult.Skipped
    }
}
