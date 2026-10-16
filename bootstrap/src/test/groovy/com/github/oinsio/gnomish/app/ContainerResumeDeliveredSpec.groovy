package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState

/**
 * FR4 of fix-terminal-receipt-convergence (task 3.4), container twin of {@link
 * GitResumeDeliveredSpec}: {@code run --resume} of a sandboxed task whose cleanup commit already
 * landed — the {@code Delivered} shape — reports the task done and writes nothing, materializing
 * no environment; a second resume after a lost-cleanup convergence is the same no-op.
 */
class ContainerResumeDeliveredSpec extends ContainerResumeSpecBase {

    // Scenario: cli/manual-run / Completed task resumes to a no-op
    def "resuming a task whose cleanup already landed reports it done and writes nothing"() {
        given: 'a completed task whose cleanup commit already landed'
        deliveredBranch('T-DELIVERED')
        def tipBefore = tip('T-DELIVERED')
        def registerBefore = register('T-DELIVERED')
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')

        when:
        resume('T-DELIVERED', null, System.out)

        then: 'the done report names the task'
        captured.toString('UTF-8').contains('Task: T-DELIVERED')

        and: 'nothing was written and no environment was touched'
        tip('T-DELIVERED') == tipBefore
        register('T-DELIVERED') == registerBefore
        docker.runs.isEmpty()

        cleanup:
        System.out = originalOut
    }

    // Scenario: cli/manual-run / Completed task with a lost cleanup converges
    def "a second resume after a lost cleanup converged writes nothing"() {
        given: 'a completed task whose cleanup commit never landed'
        repository.createTask(context('T-LOST'), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        commitTaskJson('T-LOST', new TaskOutcome.Completed(pipelineEndState()), null)
        def tipBefore = tip('T-LOST')

        when: 'the first resume lands the cleanup'
        resume('T-LOST', null, sink())

        then:
        gitOutput(cloneDir, 'rev-parse', TaskIdSanitizer.branchName('T-LOST') + '^') == tipBefore
        gitOutput(cloneDir, 'ls-tree', TaskIdSanitizer.branchName('T-LOST'), '--', '.gnomish-task') == ''

        when: 'the second resume meets the cleaned-up tip'
        def cleanedTip = tip('T-LOST')
        def registerBefore = register('T-LOST')
        resume('T-LOST', null, sink())

        then: 'it writes nothing'
        tip('T-LOST') == cleanedTip
        register('T-LOST') == registerBefore
    }

    def "a decision on a task whose cleanup already landed is a usage error and writes nothing"() {
        given:
        deliveredBranch('T-DELIVERED-D')
        def tipBefore = tip('T-DELIVERED-D')

        when:
        resume('T-DELIVERED-D', 'patch it', sink())

        then:
        def e = thrown(UsageException)
        e.message.contains('completed')

        and:
        tip('T-DELIVERED-D') == tipBefore
    }

    private void deliveredBranch(String taskId) {
        repository.createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        commitStateAtPipelineEnd(taskId)
        commitTaskJson(taskId, new TaskOutcome.Completed(pipelineEndState()), null)
        repository.finishCleanup(taskId)
        assert gitOutput(cloneDir, 'ls-tree', TaskIdSanitizer.branchName(taskId), '--', '.gnomish-task') == ''
    }

    private String tip(String taskId) {
        gitOutput(cloneDir, 'rev-parse', TaskIdSanitizer.branchName(taskId))
    }

    private String register(String taskId) {
        gitOutput(cloneDir, 'rev-parse', "refs/gnomish-factory/${taskId}")
    }
}
