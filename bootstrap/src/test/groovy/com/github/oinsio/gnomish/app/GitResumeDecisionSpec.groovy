package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files

/**
 * FR3, FR4, FR9, FR10, NFR-R1 of make-run-headless (design D2, D7, D8): {@code gnomish run
 * --resume}'s {@code escalated} arm on the host, over a real clone and a bare origin — the
 * operator's {@code --decision} resolved through {@code EscalationResume} and landed with the
 * attempts reset in ONE commit; an {@code AttemptsExhausted} resumed without a decision rerun and
 * re-parked identically with the park recorded once; a {@code DecisionNeeded} resumed without a
 * decision refused with nothing written; a decision over a non-escalated outcome refused as a usage
 * error with nothing written. Standard input is empty throughout: a headless resume reads nothing
 * (FR6).
 */
class GitResumeDecisionSpec extends GitResumeSpecBase {

    // FR5, FR8, UX2; FR3, NFR-R1 of make-run-headless: outcome escalated with --decision appends
    // the decision and resets the attempts in ONE commit — the first commit after the park — pushed
    // to the bare origin, then continues to completion. No prompt is printed, nothing is read.
    def "run() with outcome escalated and --decision lands the decision and the reset in one commit, then continues to completion"() {
        given: 'a task branch on a real origin, escalated after one persisted round'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        def taskId = 'PROJ-11'
        def branch = TaskIdSanitizer.branchName(taskId)
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def afterRound = new TaskState(new Position.AtStage('build'), 2, [], ExecutorUsage.none())
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded(UntrustedText.agent('continue?'), [
            UntrustedText.agent('yes'),
            UntrustedText.agent('no')
        ])
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(afterRound, report), TrackerWrite.OWED)
        def parkTip = gitOutput(cloneDir, 'rev-parse', branch).trim()
        def out = new ByteArrayOutputStream()

        when: 'stdin is empty; the decision is the flag'
        newResumeRunner(new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, 'UTF-8'))
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, 'go ahead')

        then: 'no prompt and no restated question reached the console'
        def printed = out.toString('UTF-8')
        !printed.contains('Decision (empty to resume without one)')
        !printed.contains('continue?')

        and: 'the task reached completion and the worktree is gone'
        gitExitCode(cloneDir, 'rev-parse', '--verify', branch) == 0
        !Files.exists(expectedWorktree(taskId))

        and: 'NFR-R1: the commit right after the park carries BOTH the decision and the attempts reset, on the bare origin'
        def decisionCommit = gitOutput(bare, 'rev-list', '--ancestry-path', '--reverse', "${parkTip}..refs/heads/${branch}")
                .readLines().first().trim()
        def taskJson = gitOutput(bare, 'show', "${decisionCommit}:.gnomish-task/task.json")
        taskJson.contains('go ahead')
        taskJson.contains('"outcome":null')
        def stateJson = gitOutput(bare, 'show', "${decisionCommit}:.gnomish-task/state.json")
        stateJson.contains('"attemptsUsed":0')
        gitOutput(bare, 'show', "${parkTip}:.gnomish-task/state.json").contains('"attemptsUsed":2')
    }

    // FR3, FR10 of make-run-headless (design D8, "Idempotence"; task 2.6): an AttemptsExhausted
    // park resumed without --decision resets the attempts in memory only, reruns the round and
    // exhausts the same limit again. The re-park's task.json is byte for byte the one on the tip,
    // so recordOutcome makes no second lifecycle commit and the resume ends on RunParkedException
    // (exit 10) — not on git's "nothing to commit" surfaced as a GitTaskRepositoryException.
    def "run() over an AttemptsExhausted park and no --decision reruns, re-parks identically and records the park once"() {
        given: 'a task on a real origin, parked with its attempts exhausted, as a run park records it'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        def taskId = 'PROJ-16'
        def branch = TaskIdSanitizer.branchName(taskId)
        def pipeline = ParkPipelines.escalating()
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def spent = new TaskState(new Position.AtStage('build'), 1, [], ExecutorUsage.none())
        persistOneRound(taskId, spent)
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(spent, new EscalationReport.AttemptsExhausted(1)), TrackerWrite.NONE)
        def parkedTaskJson = gitOutput(cloneDir, 'show', "${branch}:.gnomish-task/task.json")

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'))
                .run(new RunOrder(cloneDir, null, pipeline, false), taskId, null)

        then: 'the rerun parks on the same spent limit'
        def stop = thrown(RunParkedException)
        (stop.outcome() as TaskOutcome.Escalated).report() == new EscalationReport.AttemptsExhausted(1)

        and: 'the tip carries the identical document, with one escalation commit in its history, on both replicas'
        gitOutput(cloneDir, 'show', "${branch}:.gnomish-task/task.json") == parkedTaskJson
        gitOutput(cloneDir, 'log', '--format=%s', branch).readLines().count {
            it == 'gnomish: task escalated'
        } == 1
        gitOutput(bare, 'rev-parse', "refs/heads/${branch}") == gitOutput(cloneDir, 'rev-parse', branch)
    }

    // FR4 of make-run-headless: a DecisionNeeded resumed without --decision is refused — the
    // question is restated with the return path, nothing is written, no round runs.
    def "run() with outcome escalated over a DecisionNeeded and no --decision restates the question and writes nothing"() {
        given:
        def taskId = 'PROJ-14'
        def branch = TaskIdSanitizer.branchName(taskId)
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded(UntrustedText.agent('continue?'), [UntrustedText.agent('yes')])
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(afterRound, report), TrackerWrite.OWED)
        def tipBefore = gitOutput(cloneDir, 'rev-parse', branch).trim()
        def out = new ByteArrayOutputStream()

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, 'UTF-8'))
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, null)

        then:
        thrown(DecisionRequiredException)
        def printed = out.toString('UTF-8')
        printed.contains('continue?')
        printed.contains("--resume=${taskId}")

        and: 'the branch tip is untouched and the worktree is kept'
        gitOutput(cloneDir, 'rev-parse', branch).trim() == tipBefore
        Files.isDirectory(expectedWorktree(taskId))
    }

    // FR9 of make-run-headless: a --decision over a paused task is a usage error naming the
    // conflict, raised before any branch write.
    def "run() with --decision over a paused task is a usage error that writes nothing"() {
        given:
        def taskId = 'PROJ-15'
        def branch = TaskIdSanitizer.branchName(taskId)
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def endState = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())
        persistOneRound(taskId, endState)
        repository().recordOutcome(taskId, new TaskOutcome.Paused(endState, 'build'), TrackerWrite.OWED)
        def tipBefore = gitOutput(cloneDir, 'rev-parse', branch).trim()

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, 'nobody asked')

        then:
        def e = thrown(UsageException)
        e.message.contains('--decision')
        e.message.contains(taskId)
        e.message.contains("paused")

        and:
        gitOutput(cloneDir, 'rev-parse', branch).trim() == tipBefore
        Files.isDirectory(expectedWorktree(taskId))
    }
}
