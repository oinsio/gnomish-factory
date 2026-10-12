package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2 of fix-denial-attribution-durability, FR7, FR8 of make-checkpoint-gate-durable, FR5 of
 * add-git-workflow — what {@code task.json} keeps of an escalation on the real medium, in host
 * mode: the denials of a round that could not execute (an empty list included), what of the
 * escalation survives the visit that answers it, and where the superseded record stays readable.
 */
class EscalationRecordLifetimeSpec extends Specification implements BareGitRepoFixture {

    private static final String TASK = 'PROJ-1'
    private static final String TASK_JSON = '.gnomish-task/task.json'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    RegisteredClone registeredClone
    GitTaskRepository repository

    def setup() {
        Path cloneDir = initWorkingRepo(tempDir, 'clone')
        commit(cloneDir, 'a.txt', 'first')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        repository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
        repository.createTask(new TaskContext(TASK, UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
    }

    private Path worktree() {
        registeredClone.worktrees().resolve(TASK)
    }

    private String taskJsonAt(String commit) {
        gitOutput(worktree(), 'show', "${commit}:${TASK_JSON}")
    }

    private TaskRecord recordAt(String commit) {
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(UntrustedText.branchDocument(taskJsonAt(commit))))
    }

    private void park(EscalationReport report) {
        repository.recordOutcome(TASK, new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report), TrackerWrite.OWED)
    }

    // FR2 of fix-denial-attribution-durability: the field is written whatever the round produced —
    //     an empty array, never an omitted field or a null a reader would have to interpret.
    // Scenario: git/git-task-persistence / A round with no denial records an empty list
    def "FR2: a cannotExecute escalation for a round with no denial carries an empty denials array"() {
        when:
        park(new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out'), []))

        then:
        def denials = TaskStateJson.mapper().readTree(taskJsonAt('HEAD')).get('lastEscalation').get('denials')
        denials != null
        denials.isArray()
        denials.size() == 0
    }

    // FR2 of fix-denial-attribution-durability: the resumed write and later work supersede the
    //     escalation at the tip; the park commit itself stays in history, denials and all.
    // Scenario: git/git-task-persistence / Escalation denials survive the resume
    def "FR2: a superseded cannotExecute escalation and its denials stay readable in task.json's history"() {
        given: 'a park whose round tried a denied egress request'
        def denial = Denial.unidentified(new Finding('egress denied: paste.example.com:443', 'paste.example.com:443/upload', null))
        def escalation = new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out'), [denial])
        park(escalation)

        when: 'the task is resumed and a later visit escalates for another reason'
        repository.resumeFrom(TASK, TaskState.atStageStart('implement'))
        park(new EscalationReport.AttemptsExhausted(3))

        then: 'the tip no longer carries the first escalation'
        recordAt('HEAD').lastEscalation() == new EscalationReport.AttemptsExhausted(3)

        and: 'the park commit in task.json\'s history still reads back the escalation with its denial'
        def commits = gitOutput(worktree(), 'log', '--format=%H', "--grep=${ServiceCommitMessages.taskEvent(TaskLifecycleEvent.ESCALATED)}", '--fixed-strings', 'HEAD').readLines()
        commits.size() == 2
        recordAt(commits.last()).lastEscalation() == escalation
        (recordAt(commits.last()).lastEscalation() as EscalationReport.CannotExecute).denials() == [denial]
    }

    // FR7, FR8 of make-checkpoint-gate-durable: the decision commit resets only the outcome; the
    //     last escalation stays as display history beside the appended answer. That no recovery
    //     decision reads it on its own is the routing predicate's row "null outcome + AttemptsExhausted"
    //     in TakeLoadedBranchRoutesSpec.
    // Scenario: git/git-task-persistence / The last escalation outlives the visit and drives no recovery
    def "FR8: the decision commit resets the outcome and keeps lastEscalation with the question beside the answer"() {
        given:
        def question = new EscalationReport.DecisionNeeded(UntrustedText.agent('which database?'), [
            UntrustedText.agent('postgres'),
            UntrustedText.agent('sqlite')
        ])
        park(question)
        def answer = new Decision('postgres', 'implement', 'operator', null)

        when:
        repository.appendDecision(TASK, answer, TaskState.atStageStart('implement'))

        then: 'the visit is open again: no outcome at the tip'
        def record = recordAt('HEAD')
        record.outcome() == null

        and: 'lastEscalation, kept apart from outcome, still carries the question, and the answer sits in decisions'
        record.lastEscalation() == question
        record.context().decisions() == [answer]
    }
}
