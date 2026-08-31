package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1 of add-git-workflow: {@code TaskRepository}'s git realization — create branch + first
 * task.json commit, append decision (resetting outcome), record outcome/escalation, per the
 * ServiceCommitMessages scheme (D14).
 */
class GitTaskRepositorySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    Path worktreesRoot
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
        repository = new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
    }

    private static TaskContext sampleContext(String taskId = 'PROJ-1', List<Decision> decisions = []) {
        new TaskContext(taskId, 'Fix the thing', 'Body text', decisions)
    }

    private Path worktreeFor(String taskId) {
        worktreesRoot.resolve('clone').resolve(taskId)
    }

    private String readTaskJson(String taskId, String ref = 'HEAD') {
        runner.run(worktreeFor(taskId), 'show', "${ref}:.gnomish-task/task.json").stdout()
    }

    def "FR1: createTask creates the branch and commits task.json with the STARTED message"() {
        given:
        def context = sampleContext()

        when:
        repository.createTask(context, null, TaskState.atStageStart('implement'))

        then: 'the branch exists in the clone'
        runner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-1').exitCode() == 0

        and: 'the worktree carries a commit with the STARTED message'
        def worktree = worktreeFor('PROJ-1')
        def message = runner.run(worktree, 'log', '-1', '--format=%s').stdout().trim()
        message == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.STARTED)

        and: 'task.json round-trips the context with null outcome/lastEscalation'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.context() == context
        content.outcome() == null
        content.lastEscalation() == null
        content.baseCommit() != null
    }

    // FR3 of harden-task-branch-contract: the STARTED commit carries the initial state.json
    // beside task.json. One commit, not two — a run that dies before its first round completes
    // still leaves a branch the next resume can read, which is the crash loop FR3 closes.
    def "FR3: the STARTED commit carries the initial state.json beside task.json"() {
        when:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))

        then: 'the STARTED commit itself — not a later one — holds state.json'
        def worktree = worktreeFor('PROJ-1')
        def files = runner.run(worktree, 'show', '--name-only', '--format=', 'HEAD').stdout()
        files.contains('.gnomish-task/task.json')
        files.contains('.gnomish-task/state.json')

        and: 'it records the pipeline\'s first stage with nothing burned yet'
        def state = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        runner.run(worktree, 'show', 'HEAD:.gnomish-task/state.json').stdout()))
        (state.position() as Position.AtStage).name() == 'implement'
        state.attemptsUsed() == 0
        state.attempts().isEmpty()
    }

    // FR4 of harden-task-branch-contract: the decision and the attempt-counter reset it implies
    // are one transition, so they are ONE commit — no tip ever reads "answered, but still
    // exhausted", which a resume would turn straight back into an escalation.
    def "FR4: the decision commit carries the attempt-counter reset"() {
        given: 'a task whose stage burned an attempt before parking, recorded on the branch'
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))
        def worktree = worktreeFor('PROJ-1')
        def burned = TaskState.atStageStart('implement').recordQualityFailure(new AttemptRecord(
                        0, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [],
                        ExecutorUsage.none(), JudgeUsage.none(), []))
        new GitAttemptPersistence(runner, worktree, 'PROJ-1', ClaimEpochSource.NONE).persist('PROJ-1', burned,
                new ToolTrace(new AttemptKey('PROJ-1', 'implement', 0), []))
        def before = runner.run(worktree, 'rev-list', '--count', 'HEAD').stdout().trim() as Integer

        when: 'the human answer is appended with the reset it implies'
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', Instant.EPOCH),
                burned.resetAttempts())

        then: 'exactly one commit was added'
        def after = runner.run(worktree, 'rev-list', '--count', 'HEAD').stdout().trim() as Integer
        after == before + 1

        and: 'that one commit carries the decision and the reset counter together'
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1'))).context().decisions().size() == 1
        def state = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        runner.run(worktree, 'show', 'HEAD:.gnomish-task/state.json').stdout()))
        state.attemptsUsed() == 0
        (state.position() as Position.AtStage).name() == 'implement'

        and: 'the tip it replaced still showed the burn — so the reset really landed with the decision'
        def parent = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        runner.run(worktree, 'show', 'HEAD~1:.gnomish-task/state.json').stdout()))
        parent.attemptsUsed() == 1
    }

    def "FR2/D7: createTask with an explicit baseRef records that commit as baseCommit"() {
        given: 'a second commit on the clone after the base we want to pin'
        def firstHead = runner.run(cloneDir, 'rev-parse', 'HEAD').stdout().trim()
        new File(cloneDir.toFile(), 'b.txt').text = 'second'
        runner.run(cloneDir, 'add', 'b.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'second')

        when:
        repository.createTask(sampleContext(), firstHead, TaskState.atStageStart('implement'))

        then:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.baseCommit() == firstHead
    }

    def "FR1: createTask throws when the branch already exists for the taskId"() {
        given:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))

        when:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))

        then:
        thrown(GitTaskRepositoryException)
    }

    def "FR5/D9: appendDecision appends to decisions[], resets outcome to null, commits with RESUMED"() {
        given: 'a task parked with a non-null outcome'
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))
        def decision = new Decision('proceed to verify', 'implement', 'operator', null)

        when:
        repository.appendDecision('PROJ-1', decision, TaskState.atStageStart('implement'))

        then:
        def worktree = worktreeFor('PROJ-1')
        def message = runner.run(worktree, 'log', '-1', '--format=%s').stdout().trim()
        message == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.RESUMED)

        and:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.context().decisions() == [decision]
        content.outcome() == null
    }

    def "FR1: recordOutcome commits the matching message and content for each outcome variant"() {
        given:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then: 'the outcome-recording commit is the tip and carries the expected message'
        def worktree = worktreeFor('PROJ-1')
        commitMessageAt(worktree, 0) == ServiceCommitMessages.taskEvent(expectedEvent)

        and: 'task.json at the tip shows the recorded outcome — including for Completed, whose cleanup is a separate step (FR10 of harden-task-branch-contract)'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1', 'HEAD')))
        expectedKind.isInstance(content.outcome())

        where:
        outcome | expectedEvent | expectedKind
        new TaskOutcome.Completed(TaskState.atStageStart('implement')) | TaskLifecycleEvent.COMPLETED | RecordedOutcome.Completed
        new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement') | TaskLifecycleEvent.PAUSED | RecordedOutcome.Paused
        new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
                new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])) | TaskLifecycleEvent.ESCALATED | RecordedOutcome.Escalated
        new TaskOutcome.Aborted(TaskState.atStageStart('implement'),
                new AttemptKey('PROJ-1', 'implement', 0), 'boom') | TaskLifecycleEvent.ABORTED | RecordedOutcome.Aborted
    }

    private String commitMessageAt(Path worktree, int commitsBack) {
        runner.run(worktree, 'log', "-1", "--skip=${commitsBack}", '--format=%s').stdout().trim()
    }

    def "FR1: recordOutcome for Escalated populates lastEscalation"() {
        given:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))

        then:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.lastEscalation() == report
    }

    def "FR5: parked and interrupted tasks are distinguishable by outcome, side by side"() {
        given: 'two tasks escalated with a question, both resumed by a decision'
        repository.createTask(sampleContext('PROJ-PARKED'), null, TaskState.atStageStart('implement'))
        repository.createTask(sampleContext('PROJ-INTERRUPTED'), null, TaskState.atStageStart('implement'))
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repository.recordOutcome('PROJ-PARKED', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))
        repository.recordOutcome(
                'PROJ-INTERRUPTED', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))
        def decision = new Decision('proceed to verify', 'implement', 'operator', null)

        when: 'the interrupted task is resumed and its process dies mid-stage — no recordOutcome follows'
        repository.appendDecision('PROJ-INTERRUPTED', decision, TaskState.atStageStart('implement'))

        and: 'the parked task is, separately, genuinely parked again (recordOutcome IS called)'
        repository.recordOutcome('PROJ-PARKED', new TaskOutcome.Paused(TaskState.atStageStart('verify'), 'verify'))

        then: 'the interrupted task.json shows outcome null — process death is indistinguishable from "still working" by design'
        def interrupted = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-INTERRUPTED')))
        interrupted.outcome() == null

        and: 'the parked task.json shows its recorded outcome'
        def parked = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-PARKED')))
        parked.outcome() instanceof RecordedOutcome.Paused

        and: 'both preserve lastEscalation from the earlier escalation — kept separately from outcome (FR5)'
        interrupted.lastEscalation() == report
        parked.lastEscalation() == report
    }

    // FR10 of harden-task-branch-contract: the cleanup commit is the destructive last step of the
    // completion sequence, so recordOutcome leaves the envelope in place for the tracker write to
    // follow — the CompletedUncleaned shape a kill in that window freezes.
    def "FR10: recordOutcome(Completed) leaves the envelope at the tip for finishCleanup to remove"() {
        given:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))

        then: 'the tip still carries the envelope, recording Completed'
        def worktree = worktreeFor('PROJ-1')
        runner.run(worktree, 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().trim() != ''
        commitMessageAt(worktree, 0) == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.COMPLETED)
    }

    // FR10 of harden-task-branch-contract: running the destructive step twice equals running it once.
    def "FR10: finishCleanup on an already-cleaned tip changes nothing"() {
        given:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('PROJ-1')
        def worktree = worktreeFor('PROJ-1')
        def tip = runner.run(worktree, 'rev-parse', 'HEAD').stdout().trim()

        when:
        repository.finishCleanup('PROJ-1')

        then:
        runner.run(worktree, 'rev-parse', 'HEAD').stdout().trim() == tip
    }

    def "FR15/M4: finishCleanup adds the cleanup commit removing .gnomish-task/ from the tip, full history preserved"() {
        given: 'a task with at least one round commit before completion, to prove earlier history stays reachable'
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', null), TaskState.atStageStart('implement'))
        def worktree = worktreeFor('PROJ-1')
        def commitCountBeforeCompletion = commitCount(worktree)

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('PROJ-1')

        then: 'the tip has no .gnomish-task/ directory on disk'
        !new File(worktree.toFile(), '.gnomish-task').exists()

        and: 'the tip has no .gnomish-task/ directory in the git tree either'
        runner.run(worktree, 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().trim() == ''

        and: 'the last commit is the cleanup commit'
        commitMessageAt(worktree, 0) == ServiceCommitMessages.cleanup()

        and: 'the second-to-last commit is the COMPLETED outcome commit'
        commitMessageAt(worktree, 1) == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.COMPLETED)

        and: 'the completed task.json is still readable from the second-to-last commit — history preserved'
        def completedSha = runner.run(worktree, 'log', '-1', '--skip=1', '--format=%H').stdout().trim()
        def historicalJson = runner.run(worktree, 'show', "${completedSha}:.gnomish-task/task.json").stdout()
        def historicalContent = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(historicalJson))
        historicalContent.outcome() instanceof RecordedOutcome.Completed

        and: 'every earlier round/lifecycle commit is still reachable — two extra commits appended on top'
        commitCount(worktree) == commitCountBeforeCompletion + 2
    }

    def "FR15/M4: recordOutcome for non-Completed outcomes never adds a cleanup commit, .gnomish-task/ stays"() {
        given:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))
        def worktree = worktreeFor('PROJ-1')

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then: '.gnomish-task/ is still present on disk and at HEAD'
        new File(worktree.toFile(), '.gnomish-task').exists()
        runner.run(worktree, 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().trim() != ''

        and: 'no commit carries the cleanup message'
        allCommitMessages(worktree).every {
            it != ServiceCommitMessages.cleanup()
        }

        where:
        outcome << [
            new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'),
            new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
            new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])),
            new TaskOutcome.Aborted(TaskState.atStageStart('implement'),
            new AttemptKey('PROJ-1', 'implement', 0), 'boom'),
        ]
    }

    // FR10, D10 of add-claim-heartbeat: recording a terminal PARK sets the durable "tracker-write
    // pending" marker before its (git-unfenced) tracker write, so a resuming instance can tell an
    // orphaned park from a settled one.
    def "FR10: recordOutcome for a park (#event) sets the tracker-write pending marker, round-tripping the branch"() {
        given:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then: 'a fresh read of the branch tip sees the pending marker'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.trackerWritePending()

        where:
        event | outcome
        'ESCALATED' | new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
                new EscalationReport.DecisionNeeded('continue?', ['yes', 'no']))
        'PAUSED' | new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement')
    }

    // FR10, D10: a non-park terminal outcome never sets the marker (Aborted's write is best-effort;
    // Completed's reconcile is decided by cleanup-detection, not the marker).
    def "FR10: recordOutcome for Aborted leaves the tracker-write pending marker unset"() {
        given:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome(
                'PROJ-1',
                new TaskOutcome.Aborted(TaskState.atStageStart('implement'), new AttemptKey('PROJ-1', 'implement', 0),
                'boom'))

        then:
        !TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1'))).trackerWritePending()
    }

    // FR10, D10: once the park's tracker write confirms, confirmTerminalWrite clears the marker in a
    // new commit, preserving the recorded outcome and escalation.
    def "FR10: confirmTerminalWrite clears the pending marker while preserving the recorded park outcome"() {
        given:
        repository.createTask(sampleContext(), null, TaskState.atStageStart('implement'))
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))

        when:
        repository.confirmTerminalWrite('PROJ-1')

        then: 'the marker is cleared'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        !content.trackerWritePending()

        and: 'the recorded outcome and escalation survive the clear'
        content.outcome() != null
        content.lastEscalation() == report

        and: 'the clear is a dedicated write-confirmed commit at the tip'
        def worktree = worktreeFor('PROJ-1')
        runner.run(worktree, 'log', '-1', '--format=%s').stdout().trim() ==
                ServiceCommitMessages.trackerWriteConfirmed()
    }

    private int commitCount(Path worktree) {
        runner.run(worktree, 'rev-list', '--count', 'HEAD').stdout().trim() as int
    }

    private List<String> allCommitMessages(Path worktree) {
        runner.run(worktree, 'log', '--format=%s').stdout().readLines()
    }
}
