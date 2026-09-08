package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskState

/**
 * FR12, design D13 of add-base-ref-resolution: {@link TakeResumeRunner} wires {@link
 * ResumeLawBinding} in front of its execution tail — a ref that resolves nowhere parks instead of
 * running the engine, a configured origin that never answers releases the claim instead of parking.
 * The branch logic itself is unit-tested exhaustively in {@code ResumeLawBindingSpec}; this proves
 * the wiring: {@link TakeResumeRunner#resumeWithoutDecision} reaches {@link BaseRefGit
 * #resolveForResume} with the task's recorded base and routes every outcome correctly.
 */
class TakeResumeRunnerLawBindingSpec extends TakeResumeSpecBase {

    private static TaskGit gitWith(BaseRefGit baseRefGit) {
        def real = TaskGitFixture.real()
        new TaskGit(real.store(), real.branches(), real.worktrees(), real.midRoundPush(), baseRefGit)
    }

    // FR12, D13: the resolved tip law-binds the run and the engine proceeds normally — the
    // ordinary path once task 6.3 lands real ref-name pins.
    def "resumeWithoutDecision binds the resolved tip and runs the engine"() {
        given:
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), 'HEAD', BaseRule.LOCAL_HEAD, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def resolved = gitOutput(cloneDir, 'rev-parse', "gnomish/${taskId}")
        def baseRefGit = Stub(BaseRefGit)
        baseRefGit.resolveForResume(cloneDir, _ as String) >> new ResumeBaseOutcome.Bound('irrelevant', resolved)
        def runner = newTakeResumeRunner(new ByteArrayInputStream((System.lineSeparator() * 20).getBytes('UTF-8')), testProperties(), [], new ClaimLossFlag(), gitWith(baseRefGit))
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(), state, RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then:
        result instanceof TakeResult.Delivered
    }

    // D13: a pinned ref that resolves nowhere parks the task instead of running the engine — the
    // worktree is left exactly as bootstrap materialized it, no round attempted.
    def "resumeWithoutDecision parks INFRA and never runs the engine when the ref resolves nowhere"() {
        given:
        def taskId = 'PROJ-2'
        repository().createTask(context(taskId), 'HEAD', BaseRule.LOCAL_HEAD, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def baseRefGit = Stub(BaseRefGit)
        baseRefGit.resolveForResume(cloneDir, _ as String) >> new ResumeBaseOutcome.Refused('gone')
        def runner = newTakeResumeRunner(new ByteArrayInputStream((System.lineSeparator() * 20).getBytes('UTF-8')), testProperties(), [], new ClaimLossFlag(), gitWith(baseRefGit))
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(), state, RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then:
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.INFRA
        1 * tracker.park(REF, ParkReason.INFRA, _)
    }

    // D9, D13: a configured origin that never answers releases the claim rather than parking —
    // the daemon's infrastructure condition, never the task's.
    def "resumeWithoutDecision releases the claim when a configured origin never answers"() {
        given:
        def taskId = 'PROJ-3'
        repository().createTask(context(taskId), 'HEAD', BaseRule.LOCAL_HEAD, TaskState.atStageStart('build'))
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def baseRefGit = Stub(BaseRefGit)
        baseRefGit.resolveForResume(cloneDir, _ as String) >> new ResumeBaseOutcome.Unavailable('no answer')
        def runner = newTakeResumeRunner(new ByteArrayInputStream((System.lineSeparator() * 20).getBytes('UTF-8')), testProperties(), [], new ClaimLossFlag(), gitWith(baseRefGit))
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(), state, RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        1 * tracker.release(REF)
    }
}
