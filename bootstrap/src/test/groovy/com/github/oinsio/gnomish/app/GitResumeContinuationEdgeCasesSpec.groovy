package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files

/**
 * FR5, FR8, FR10, D1, D10 of add-git-workflow: two {@link GitResumeContinuation} scenarios not
 * covered elsewhere in the suite without them being masked by later cleanup. Split out from the
 * original GitResumeRunner spec suite (now {@link GitResumeOutcomeSpec}, {@link
 * GitResumeBootstrapSpec}, and this file) to keep each file within the file-size guidance
 * (.claude/rules/process-invariants.md):
 *
 * <ul>
 *   <li>{@code GitResumeContinuation#recordDecisionIfAppended} only appends a
 *       {@link com.github.oinsio.gnomish.domain.engine.Decision} through
 *       {@link com.github.oinsio.gnomish.adapter.git.GitTaskRepository#appendDecision} when the escalation dialog actually grew
 *       the context's decision list — a blank (bare Enter) answer resumes without one (FR9 of
 *       add-manual-run). PIT: ConditionalsBoundaryMutator on the {@code after > before} comparison
 *       — this is the one scenario in the suite where no decision is appended at all.
 *   <li>{@link GitResumeContinuation#resumeFromRecordedPosition}'s {@code --discard-work} path
 *       calls {@link com.github.oinsio.gnomish.adapter.git.WorktreeSalvage#discard}. {@link
 *       GitResumeOutcomeSpec}'s own discard-work scenario checks the leftover file's absence only
 *       from the live worktree — but a completed task's worktree is unconditionally removed by
 *       {@link GitOutcomeRecorder} regardless of whether discard ran, so that check alone cannot
 *       distinguish the mutant (PIT: VoidMethodCallMutator survivor). This spec instead proves it
 *       through the branch's own commit history, which a removed worktree does not erase.
 * </ul>
 */
class GitResumeContinuationEdgeCasesSpec extends GitResumeSpecBase {

    // FR5, FR8, D1; FR4 of make-run-headless: a resume without --decision must NOT append a
    // Decision — proven by the historical task.json blobs never carrying a decision entry, unlike
    // GitResumeOutcomeSpec's --decision scenario where the flag's text does land one.
    def "run() with outcome escalated and no --decision resumes without appending a decision"() {
        given: 'a task escalated after one persisted round'
        def taskId = 'PROJ-40'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        // An AttemptsExhausted park: the one escalation kind a resume may continue without a
        // decision (a DecisionNeeded without one is refused — GitResumeDecisionSpec).
        def report = new EscalationReport.AttemptsExhausted(3)
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(afterRound, report), TrackerWrite.OWED)

        and: 'stdin is empty: a headless resume reads nothing (FR4, FR6 of make-run-headless)'
        def out = new ByteArrayOutputStream()

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, 'UTF-8'))
                .run(new RunOrder(cloneDir, null, pipeline(), false), taskId, null)

        then: 'the task still reaches completion'
        gitExitCode(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}") == 0
        !Files.exists(expectedWorktree(taskId))

        and: 'no decision was ever appended — every historical task.json still carries an empty decisions list'
        def historicalTaskJsons = gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--format=%H')
                .lines().collect { it as String }
                .findAll {
                    gitExitCode(cloneDir, 'show', "${it}:.gnomish-task/task.json") == 0
                }
                .collect {
                    gitOutput(cloneDir, 'show', "${it}:.gnomish-task/task.json")
                }
        historicalTaskJsons.every { !it.contains('"decisions":[{') }
    }

    // FR10, D10, PIT VoidMethodCallMutator: --discard-work must call WorktreeSalvage#discard —
    // proven through the branch's own commit history (never erased by the later worktree-removal
    // cleanup on completion, unlike GitResumeOutcomeSpec's live-worktree check), so a leftover file
    // that discard() should have wiped before the round commit never appears in ANY commit's tree.
    def "run() with --discard-work never commits the discarded leftover to branch history"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-41'
        repository().createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work')

        when: 'resuming with --discard-work drives the task to completion'
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .run(new RunOrder(cloneDir, null, pipeline(), true), taskId, null)

        then: 'half-done.txt never appears in any commit on the branch, even though the worktree itself was later removed'
        def allBlobPaths = gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--name-only', '--format=')
        !allBlobPaths.contains('half-done.txt')
    }
}
