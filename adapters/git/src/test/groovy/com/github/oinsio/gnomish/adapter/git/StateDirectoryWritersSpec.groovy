package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3 of harden-task-branch-contract, FR13 of make-checkpoint-gate-durable, FR2 of
 * add-git-workflow — the state directory's writers on the real medium, in host mode: who writes
 * {@code state.json} after the STARTED commit, and what a repeated stage visit does to the round
 * trace it leaves at the tip. A lifecycle {@link GitTaskRepository} and a round
 * {@link GitAttemptPersistence} over one task worktree, the way a run drives them.
 */
class StateDirectoryWritersSpec extends Specification implements BareGitRepoFixture {

    private static final String TASK = 'PROJ-1'
    private static final String STATE = '.gnomish-task/state.json'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    RegisteredClone registeredClone
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        commit(cloneDir, 'a.txt', 'first')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        repository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
        repository.createTask(new TaskContext(TASK, UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
    }

    private Path worktree() {
        registeredClone.worktrees().resolve(TASK)
    }

    private void round(TaskState state, int round, int toolCalls) {
        def calls = (0..<toolCalls).collect {
            new ToolCall(it, "tool-${it}", Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(10))
        }
        new GitAttemptPersistence(runner, worktree(), TASK, ClaimEpochSource.NONE)
                .persist(TASK, state, new ToolTrace(new AttemptKey(TASK, 'implement', round), calls))
    }

    private List<String> stateWritesAfterStart() {
        def started = gitOutput(worktree(), 'log', '--format=%H', '--reverse', 'HEAD', '--', '.gnomish-task/task.json').readLines().first()
        gitOutput(worktree(), 'log', '--format=%H %s', "${started}..HEAD", '--', STATE).readLines().reverse()
    }

    private TaskState stateAt(String commit) {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(gitOutput(worktree(), 'show', "${commit}:${STATE}"))))
    }

    // FR3 of harden-task-branch-contract; FR3, FR7 of make-checkpoint-gate-durable: after STARTED,
    //     state.json moves only in a round commit or in a lifecycle commit of its own — the decision,
    //     the approval, the resumed write — and every lifecycle write carries the state it was handed.
    // Scenario: git/git-task-persistence / state.json has two writers in disjoint windows
    def "FR3: state.json is written only by a round commit or by its own decision, approval or resumed commit"() {
        given:
        // Each write moves the state somewhere new, so every one of them is a change git records.
        def reset = TaskState.atStageStart('implement').resetAttempts()
        def continued = TaskState.atStageStart('deliver')
        def gate = TaskState.atStageStart('implement').advanceTo(new Position.AwaitingApproval('implement'))
        def approved = TaskState.atStageStart('verify')

        when: 'a round, a park answered by a decision, a round at the gate approved, a park continued without a reply'
        round(TaskState.atStageStart('review'), 0, 1)
        repository.recordOutcome(TASK, new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
                new EscalationReport.DecisionNeeded(UntrustedText.agent('which?'), [
                    UntrustedText.agent('a'),
                    UntrustedText.agent('b')
                ])), TrackerWrite.OWED)
        repository.appendDecision(TASK, new Decision('a', 'implement', 'operator', null), reset)
        round(gate, 1, 1)
        repository.recordOutcome(TASK, new TaskOutcome.Paused(gate, 'implement'), TrackerWrite.OWED)
        repository.approveCheckpoint(TASK, new Position.AwaitingApproval('implement'), approved)
        repository.recordOutcome(TASK, new TaskOutcome.Escalated(approved, new EscalationReport.AttemptsExhausted(3)), TrackerWrite.OWED)
        repository.resumeFrom(TASK, continued)
        def writes = stateWritesAfterStart()

        then: 'exactly these commits touched state.json, in this order — no outcome commit among them'
        writes.collect { it.substring(41) } == [
            ServiceCommitMessages.round('implement', 0),
            ServiceCommitMessages.taskEvent(TaskLifecycleEvent.RESUMED),
            ServiceCommitMessages.round('implement', 1),
            ServiceCommitMessages.taskEvent(TaskLifecycleEvent.APPROVED),
            ServiceCommitMessages.taskEvent(TaskLifecycleEvent.RESUMED),
        ]

        and: 'each lifecycle commit carries exactly the state its transition handed it'
        stateAt(writes[1].substring(0, 40)) == reset
        stateAt(writes[3].substring(0, 40)) == approved
        stateAt(writes[4].substring(0, 40)) == continued

        and: 'a lifecycle commit is a commit of its own, never inside a round commit'
        [1, 3, 4].every { int i ->
            gitOutput(worktree(), 'show', '--name-only', '--format=', writes[i].substring(0, 40)).readLines()
            .every {
                it.startsWith('.gnomish-task/') && !it.startsWith('.gnomish-task/attempts/')
            }
        }
    }

    // FR2 of add-git-workflow, FR13 of make-checkpoint-gate-durable: the trace path is keyed by
    //     stage and round only, so a later visit of the stage writes the same path again — the tip
    //     shows the latest visit's round, history keeps the earlier one.
    // Scenario: git/git-task-persistence / A repeated stage visit overwrites the round trace at the tip
    def "FR2: a repeated stage visit overwrites the round trace at the tip and history keeps the earlier one"() {
        given: 'a first visit whose round 0 made three tool calls'
        round(TaskState.atStageStart('implement'), 0, 3)
        def trace = '.gnomish-task/attempts/implement/0/trace.jsonl'

        when: 'the stage is visited again and its round 0 makes two'
        round(TaskState.atStageStart('implement'), 0, 2)

        then: 'the tip holds one JSON line per tool call of the latest visit'
        def lines = gitOutput(worktree(), 'show', "HEAD:${trace}").readLines()
        lines.size() == 2
        lines.collect {
            TaskStateJson.mapper().readTree(it).get('tool').asText()
        } == ['tool-0', 'tool-1']

        and: 'git history keeps the earlier visit\'s version of the same file'
        def versions = gitOutput(worktree(), 'log', '--format=%H', 'HEAD', '--', trace).readLines()
        versions.size() == 2
        gitOutput(worktree(), 'show', "${versions[1]}:${trace}").readLines().size() == 3
    }
}
