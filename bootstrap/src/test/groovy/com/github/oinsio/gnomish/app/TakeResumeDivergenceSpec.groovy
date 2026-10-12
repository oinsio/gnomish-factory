package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.SeedTransferFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.lease.EpochRecordingTracker
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path

/**
 * `tracker-take` "Divergence resolves automatically under the lease", through the explicit take
 * mandate over real git: a claimed {@code take <ref>} of a task whose branch already exists, with
 * the local branch and origin in each relation the requirement names. The claim is recorded the way
 * production records it ({@link EpochRecordingTracker} over the bundle's book), so the resume runs
 * under a held tenure.
 *
 * <p>FR8 of harden-task-branch-contract; FR9, FR11 of add-tracker-port.
 */
class TakeResumeDivergenceSpec extends TakeResumeSpecBase implements SeedTransferFixture {

    private static final String TASK = 'PROJ-1'
    private static final String BRANCH = 'gnomish/PROJ-1'

    private Path origin() {
        tempDir.resolve('origin.git')
    }

    /** A started task with one recorded round, its branch pushed to origin. */
    private Path startedAndPushed() {
        repository().createTask(context(TASK), TaskStart.commit(cloneDir, resumableBaseRef()), TaskStart.pin(resumableBaseRef(), BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        persistOneRound(TASK, TaskState.atStageStart('build'))
        gitOutput(cloneDir, 'push', 'origin', "${BRANCH}:refs/heads/${BRANCH}")
        expectedWorktree(TASK)
    }

    /** The explicit mandate on the Ready task, claimed under a recorded tenure. */
    private TakeResult take(boolean discardWork) {
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        def task = new TrackerTask(REF, new TaskSnapshot(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none(), false)
        def run = new RunOrder(cloneDir, null, pipeline(), discardWork)
        def disposition = new TakeDisposition(
                slotWiring(newAssembly(FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.create())), false,
                TakeoverConfirmation.UNAVAILABLE)
        disposition.dispose(new TakeOrder(run, task, new EpochRecordingTracker(tracker, taskGit.epochs()), INSTANCE))
    }

    private List<String> subjects(Path repo, String rev) {
        gitOutput(repo, 'log', '--format=%s', rev).readLines()
    }

    // FR8: local ahead of an unmoved origin is the newer line — kept, then delivered to origin.
    // Scenario: tracker/tracker-take / Local-ahead is kept, not discarded
    def "a local branch ahead of an unmoved origin is kept and pushed"() {
        given: 'a commit on the local task branch that origin never received'
        def worktree = startedAndPushed()
        Files.writeString(worktree.resolve('unpushed.txt'), 'local only')
        commitAll(worktree, 'local work')
        def localTip = gitOutput(worktree, 'rev-parse', 'HEAD')

        when:
        def result = take(false)

        then:
        result instanceof TakeResult.Delivered

        and: 'nothing was discarded: the local commit is on the branch the run built on'
        gitExitCode(cloneDir, 'merge-base', '--is-ancestor', localTip, BRANCH) == 0

        and: 'and it reached origin'
        gitExitCode(origin(), 'merge-base', '--is-ancestor', localTip, "refs/heads/${BRANCH}") == 0
    }

    // FR8: --discard-work names the interrupted round's leftovers, not the divergence arbitration.
    // Scenario: tracker/tracker-take / Discard-work plays no role in divergence
    def "a truly diverged branch resets to the origin tip alike with discardWork=#discardWork"() {
        given: 'origin gained a commit from another host, and the local branch one of its own'
        def worktree = startedAndPushed()
        def seed = tempDir.resolve('divergence-seed')
        seedClone(tempDir, origin().toString(), seed, '--branch', BRANCH)
        commit(seed, 'origin-only.txt', 'origin work')
        gitOutput(seed, 'push', 'origin', "${BRANCH}:refs/heads/${BRANCH}")
        def originTip = gitOutput(seed, 'rev-parse', 'HEAD')
        Files.writeString(worktree.resolve('local-only.txt'), 'local work')
        commitAll(worktree, 'local work')

        when:
        def result = take(discardWork)

        then: 'the run continued from origin in the same invocation'
        result instanceof TakeResult.Delivered
        gitExitCode(cloneDir, 'merge-base', '--is-ancestor', originTip, BRANCH) == 0

        and: 'the local line was discarded, with or without the flag'
        !subjects(cloneDir, BRANCH).contains('local work')
        !subjects(origin(), "refs/heads/${BRANCH}").contains('local work')

        where:
        discardWork << [false, true]
    }
}
