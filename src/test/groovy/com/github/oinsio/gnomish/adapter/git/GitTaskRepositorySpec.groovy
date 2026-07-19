package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
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
        repository = new GitTaskRepository(runner, cloneDir, worktreesRoot)
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
        repository.createTask(context, null)

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

    def "FR2/D7: createTask with an explicit baseRef records that commit as baseCommit"() {
        given: 'a second commit on the clone after the base we want to pin'
        def firstHead = runner.run(cloneDir, 'rev-parse', 'HEAD').stdout().trim()
        new File(cloneDir.toFile(), 'b.txt').text = 'second'
        runner.run(cloneDir, 'add', 'b.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'second')

        when:
        repository.createTask(sampleContext(), firstHead)

        then:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.baseCommit() == firstHead
    }

    def "FR1: createTask throws when the branch already exists for the taskId"() {
        given:
        repository.createTask(sampleContext(), null)

        when:
        repository.createTask(sampleContext(), null)

        then:
        thrown(GitTaskRepositoryException)
    }

    def "FR5/D9: appendDecision appends to decisions[], resets outcome to null, commits with RESUMED"() {
        given: 'a task parked with a non-null outcome'
        repository.createTask(sampleContext(), null)
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))
        def decision = new Decision('proceed to verify', 'implement', 'operator', null)

        when:
        repository.appendDecision('PROJ-1', decision)

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
        repository.createTask(sampleContext(), null)

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then: 'the outcome-recording commit carries the expected message (for Completed, this is the second-to-last commit, checked below)'
        def worktree = worktreeFor('PROJ-1')
        def message = commitMessageAt(worktree, expectedEvent == TaskLifecycleEvent.COMPLETED ? 1 : 0)
        message == ServiceCommitMessages.taskEvent(expectedEvent)

        and: 'task.json (from the outcome-recording commit for Completed, since the tip has none) shows the recorded outcome'
        def ref = expectedEvent == TaskLifecycleEvent.COMPLETED ? 'HEAD~1' : 'HEAD'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1', ref)))
        content.outcome().type() == expectedType

        where:
        outcome                                                                                  | expectedEvent                     | expectedType
        new TaskOutcome.Completed(TaskState.atStageStart('implement'))                            | TaskLifecycleEvent.COMPLETED      | 'completed'
        new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement')                  | TaskLifecycleEvent.PAUSED         | 'paused'
        new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
                new EscalationReport.DecisionNeeded('continue?', ['yes', 'no']))                  | TaskLifecycleEvent.ESCALATED      | 'escalated'
        new TaskOutcome.Aborted(TaskState.atStageStart('implement'),
                new AttemptKey('PROJ-1', 'implement', 0), 'boom')                                 | TaskLifecycleEvent.ABORTED        | 'aborted'
    }

    private String commitMessageAt(Path worktree, int commitsBack) {
        runner.run(worktree, 'log', "-1", "--skip=${commitsBack}", '--format=%s').stdout().trim()
    }

    def "FR1: recordOutcome for Escalated populates lastEscalation"() {
        given:
        repository.createTask(sampleContext(), null)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))

        then:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.lastEscalation() == report
    }

    def "FR5: parked and interrupted tasks are distinguishable by outcome, side by side"() {
        given: 'two tasks escalated with a question, both resumed by a decision'
        repository.createTask(sampleContext('PROJ-PARKED'), null)
        repository.createTask(sampleContext('PROJ-INTERRUPTED'), null)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repository.recordOutcome('PROJ-PARKED', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))
        repository.recordOutcome(
                'PROJ-INTERRUPTED', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))
        def decision = new Decision('proceed to verify', 'implement', 'operator', null)

        when: 'the interrupted task is resumed and its process dies mid-stage — no recordOutcome follows'
        repository.appendDecision('PROJ-INTERRUPTED', decision)

        and: 'the parked task is, separately, genuinely parked again (recordOutcome IS called)'
        repository.recordOutcome('PROJ-PARKED', new TaskOutcome.Paused(TaskState.atStageStart('verify'), 'verify'))

        then: 'the interrupted task.json shows outcome null — process death is indistinguishable from "still working" by design'
        def interrupted = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-INTERRUPTED')))
        interrupted.outcome() == null

        and: 'the parked task.json shows its recorded outcome'
        def parked = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-PARKED')))
        parked.outcome().type() == 'paused'

        and: 'both preserve lastEscalation from the earlier escalation — kept separately from outcome (FR5)'
        interrupted.lastEscalation() == report
        parked.lastEscalation() == report
    }

    def "FR15/M4: recordOutcome(Completed) adds a follow-up cleanup commit removing .gnomish-task/ from the tip, full history preserved"() {
        given: 'a task with at least one round commit before completion, to prove earlier history stays reachable'
        repository.createTask(sampleContext(), null)
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', null))
        def worktree = worktreeFor('PROJ-1')
        def commitCountBeforeCompletion = commitCount(worktree)

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))

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
        historicalContent.outcome().type() == 'completed'

        and: 'every earlier round/lifecycle commit is still reachable — two extra commits appended on top'
        commitCount(worktree) == commitCountBeforeCompletion + 2
    }

    def "FR15/M4: recordOutcome for non-Completed outcomes never adds a cleanup commit, .gnomish-task/ stays"() {
        given:
        repository.createTask(sampleContext(), null)
        def worktree = worktreeFor('PROJ-1')

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then: '.gnomish-task/ is still present on disk and at HEAD'
        new File(worktree.toFile(), '.gnomish-task').exists()
        runner.run(worktree, 'ls-tree', 'HEAD', '--', '.gnomish-task').stdout().trim() != ''

        and: 'no commit carries the cleanup message'
        allCommitMessages(worktree).every { it != ServiceCommitMessages.cleanup() }

        where:
        outcome << [
            new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'),
            new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
            new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])),
            new TaskOutcome.Aborted(TaskState.atStageStart('implement'),
            new AttemptKey('PROJ-1', 'implement', 0), 'boom'),
        ]
    }

    private int commitCount(Path worktree) {
        runner.run(worktree, 'rev-list', '--count', 'HEAD').stdout().trim() as int
    }

    private List<String> allCommitMessages(Path worktree) {
        runner.run(worktree, 'log', '--format=%s').stdout().readLines()
    }
}
