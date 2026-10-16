package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskState

import java.nio.file.Files
/**
 * FR4 of fix-terminal-receipt-convergence (task 3.4): host {@code run --resume} of a task whose
 * cleanup commit already landed — the {@code Delivered} shape — reports the task done from the
 * {@code Completed} commit in history and writes nothing, while an envelopeless tip that is not a
 * delivery still stops as an impossible state. The lost-cleanup convergence is {@link
 * GitResumeOutcomeSpec}; the container twin is {@link ContainerResumeDeliveredSpec}.
 */
class GitResumeDeliveredSpec extends GitResumeSpecBase {

    // FR4 of fix-terminal-receipt-convergence (task 3.4): a branch whose cleanup commit already
    // landed — the Delivered shape, no .gnomish-task/ at the registered commit — routes to the
    // same done report the completed arm prints, read from the Completed commit in history, and
    // writes nothing: no commit, no register move, no worktree left behind.
    // Scenario: cli/manual-run / Completed task resumes to a no-op
    def "run() on a branch whose cleanup already landed reports the task done and writes nothing"() {
        given: 'a completed task whose cleanup commit already landed, its worktree gone'
        def taskId = 'PROJ-14'
        deliveredBranch(taskId)
        def tipBefore = gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}")
        def registerBefore = gitOutput(cloneDir, 'rev-parse', "refs/gnomish-factory/${taskId}")

        and:
        def originalOut = System.out
        def out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, null)

        then: 'the done report names the task'
        out.toString('UTF-8').contains("Task: ${taskId}")

        and: 'nothing was written'
        gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}") == tipBefore
        gitOutput(cloneDir, 'rev-parse', "refs/gnomish-factory/${taskId}") == registerBefore
        !Files.exists(expectedWorktree(taskId))

        cleanup:
        System.out = originalOut
    }

    // FR9 of make-run-headless over the Delivered route (task 3.4): a --decision over a completed
    // task is a usage error naming the conflict, whether or not its cleanup already landed.
    // Scenario: cli/manual-run / Decision without a question is a usage error
    def "run() with a decision on a branch whose cleanup already landed is a usage error and writes nothing"() {
        given:
        def taskId = 'PROJ-15'
        deliveredBranch(taskId)
        def tipBefore = gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}")

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, 'patch it')

        then:
        def e = thrown(UsageException)
        e.message.contains('completed')

        and:
        gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}") == tipBefore
    }

    // Task 3.4: only the Delivered shape is a legal envelopeless tip. A registered commit that
    // dropped .gnomish-task/ without being the factory's cleanup commit is not a delivery, so the
    // resume still stops loudly as an impossible state instead of reporting a done task.
    def "run() on an envelopeless tip that is not a delivery still fails as an internal error"() {
        given: 'a task whose registered tip removed the envelope under a commit that is not the cleanup'
        def taskId = 'PROJ-16'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        gitRunner.run(worktree, 'rm', '-r', '-q', '.gnomish-task')
        gitRunner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'drop the envelope')
        registerTip(cloneDir, taskId)
        def tipBefore = gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}")

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, null)

        then:
        def e = thrown(InternalErrorException)
        e.message.contains(taskId)

        and:
        gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}") == tipBefore
    }

    /**
     * A Delivered branch as the factory leaves it: one round, the Completed record, then the
     * cleanup commit through the production lifecycle store, and the worktree disposed.
     */
    private void deliveredBranch(String taskId) {
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        persistOneRound(taskId, TaskState.atStageStart('build'))
        writeCompletedTaskJson(taskId)
        repository().finishCleanup(taskId)
        gitExitCode(cloneDir, 'worktree', 'remove', '--force', expectedWorktree(taskId).toString())
        assert gitOutput(cloneDir, 'ls-tree', "gnomish/${taskId}", '--', '.gnomish-task') == ''
    }
}
