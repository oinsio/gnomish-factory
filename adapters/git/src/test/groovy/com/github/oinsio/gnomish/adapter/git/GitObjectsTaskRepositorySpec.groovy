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
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR25/D19 of add-sandbox-core: the sandboxed {@code TaskRepository} realization writes the four
 * lifecycle points as bare-object plumbing commits through {@code gitobjects} — no working copy, no
 * checkout, no hooks — advancing the branch ref with an atomic compare-and-swap. Exercised against a
 * real <em>bare</em> repository, so any accidental checkout or hook execution would be observable.
 */
class GitObjectsTaskRepositorySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path bareDir
    GitObjectsTaskRepository repository

    def setup() {
        // Seed a bare repo with a base commit at refs/heads/base via a throwaway working clone.
        Path work = initWorkingRepo(tempDir, 'seed-work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work, 'init')
        bareDir = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bareDir.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')

        Path indexDir = tempDir.resolve('index')
        Files.createDirectories(indexDir)
        def gitObjects = GitObjects.open(bareDir, indexDir)
        def identity = new CommitIdentity('gnomish-factory', 'gnomish-factory@localhost')
        def clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC)
        repository = new GitObjectsTaskRepository(gitObjects, identity, clock, ClaimEpochSource.NONE)
    }

    private static TaskContext sampleContext(String taskId = 'PROJ-1', List<Decision> decisions = []) {
        new TaskContext(taskId, 'Fix the thing', 'Body text', decisions)
    }

    private String refFor(String taskId) {
        'refs/heads/gnomish/' + taskId
    }

    private String readTaskJson(String taskId, String suffix = '') {
        gitOutput(bareDir, 'show', "${refFor(taskId)}${suffix}:.gnomish-task/task.json")
    }

    def "FR25: createTask creates the branch ref and commits task.json over bare objects, no checkout"() {
        when:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        then: 'the branch ref exists in the bare clone'
        runner.run(bareDir, 'rev-parse', '--verify', refFor('PROJ-1')).exitCode() == 0

        and: 'the commit carries the STARTED lifecycle message'
        gitOutput(bareDir, 'log', '-1', '--format=%s', refFor('PROJ-1')) ==
                ServiceCommitMessages.taskEvent(TaskLifecycleEvent.STARTED)

        and: 'it is authored and committed by the factory identity'
        gitOutput(bareDir, 'log', '-1', '--format=%an <%ae> / %cn <%ce>', refFor('PROJ-1')) ==
                'gnomish-factory <gnomish-factory@localhost> / gnomish-factory <gnomish-factory@localhost>'

        and: 'task.json round-trips the context, with baseCommit = base tip and null outcome'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.context() == sampleContext()
        content.outcome() == null
        content.lastEscalation() == null
        content.baseCommit() == gitOutput(bareDir, 'rev-parse', 'base')

        and: 'the first commit builds on the base commit — the branch descends from it'
        gitOutput(bareDir, 'rev-parse', "${refFor('PROJ-1')}~1") == gitOutput(bareDir, 'rev-parse', 'base')

        and: 'no working copy is ever created in the factory-owned bare git dir'
        !Files.exists(bareDir.resolve('.gnomish-task'))
        Files.exists(bareDir.resolve('HEAD')) && !Files.exists(bareDir.resolve('.git'))
    }

    // FR3 of harden-task-branch-contract: the container-side STARTED commit carries both files
    // too — one bare-object commit with two tree edits, so no kill window freezes a branch
    // holding task.json without the state.json it implies.
    def "FR3: the bare-object STARTED commit carries the initial state.json beside task.json"() {
        when:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        then: 'one commit carries both files'
        def files = gitOutput(bareDir, 'show', '--name-only', '--format=', refFor('PROJ-1'))
        files.contains('.gnomish-task/task.json')
        files.contains('.gnomish-task/state.json')

        and: 'the recorded state is the pipeline\'s first stage with nothing burned yet'
        def state = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        gitOutput(bareDir, 'show', "${refFor('PROJ-1')}:.gnomish-task/state.json")))
        (state.position() as Position.AtStage).name() == 'implement'
        state.attemptsUsed() == 0
        state.attempts().isEmpty()
    }

    // FR4 of harden-task-branch-contract: the container-side decision is one bare-object commit
    // carrying both envelopes — the answer and the attempt-counter reset it implies.
    def "FR4: the bare-object decision commit carries the attempt-counter reset"() {
        given: 'a task branch and the state a park recorded, with an attempt burned'
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))
        def before = gitOutput(bareDir, 'rev-list', '--count', refFor('PROJ-1')) as Integer
        def burned = TaskState.atStageStart('implement').recordQualityFailure(new AttemptRecord(
                        0, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [],
                        ExecutorUsage.none(), JudgeUsage.none(), []))

        when: 'the human answer is appended with the reset it implies'
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', Instant.EPOCH),
                burned.resetAttempts())

        then: 'exactly one commit was added — the two tree edits are one transition'
        (gitOutput(bareDir, 'rev-list', '--count', refFor('PROJ-1')) as Integer) == before + 1

        and: 'that one commit carries the decision and the reset counter together'
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1'))).context().decisions().size() == 1
        def state = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        gitOutput(bareDir, 'show', "${refFor('PROJ-1')}:.gnomish-task/state.json")))
        state.attemptsUsed() == 0
        (state.position() as Position.AtStage).name() == 'implement'
    }

    def "FR25: createTask commit ids are deterministic for fixed metadata"() {
        given: 'a second, identical bare repo seeded from the same base tree'
        Path work2 = initWorkingRepo(tempDir, 'seed-work-2')
        Files.writeString(work2.resolve('a.txt'), 'first')
        commitAll(work2, 'init')
        Path bare2 = initBareRepo(tempDir, 'origin2.git')
        addRemote(work2, 'origin', bare2.toString())
        // Reuse the same base commit object so both repos share an identical base tip.
        gitOutput(work2, 'fetch', bareDir.toString(), 'base:base')
        gitOutput(work2, 'push', 'origin', 'base:refs/heads/base')
        Path index2 = tempDir.resolve('index2')
        Files.createDirectories(index2)
        def repo2 = new GitObjectsTaskRepository(
                GitObjects.open(bare2, index2),
                new CommitIdentity('gnomish-factory', 'gnomish-factory@localhost'),
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC), ClaimEpochSource.NONE)

        when:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))
        repo2.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        then: 'both branch tips are byte-identical commit objects'
        gitOutput(bareDir, 'rev-parse', refFor('PROJ-1')) == gitOutput(bare2, 'rev-parse', refFor('PROJ-1'))
    }

    def "FR25: createTask never fires a factory-clone hook (bare-object commits bypass hooks)"() {
        given: 'a pre-commit hook that would fail any hook-running commit'
        Path hook = bareDir.resolve('hooks').resolve('pre-commit')
        Files.writeString(hook, '#!/bin/sh\nexit 1\n')
        hook.toFile().setExecutable(true)

        when:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        then: 'the commit lands anyway — commit-tree/update-ref run no hooks'
        runner.run(bareDir, 'rev-parse', '--verify', refFor('PROJ-1')).exitCode() == 0
    }

    def "FR25: createTask throws when the branch already exists for the taskId"() {
        given:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        when:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        then:
        thrown(GitTaskRepositoryException)
    }

    def "FR25: createTask throws when the base ref does not resolve"() {
        when:
        repository.createTask(sampleContext(), 'no-such-ref', TaskState.atStageStart('implement'))

        then:
        thrown(GitTaskRepositoryException)
    }

    def "FR25/D9: appendDecision appends to decisions[], resets outcome to null, commits RESUMED"() {
        given: 'a task parked with a non-null outcome'
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'))
        def decision = new Decision('proceed to verify', 'implement', 'operator', null)

        when:
        repository.appendDecision('PROJ-1', decision, TaskState.atStageStart('implement'))

        then:
        gitOutput(bareDir, 'log', '-1', '--format=%s', refFor('PROJ-1')) ==
                ServiceCommitMessages.taskEvent(TaskLifecycleEvent.RESUMED)

        and:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.context().decisions() == [decision]
        content.outcome() == null
    }

    def "FR25: appendDecision throws when no branch exists for the taskId"() {
        when:
        repository.appendDecision('PROJ-1', new Decision('go', 'implement', 'op', null), TaskState.atStageStart('implement'))

        then:
        thrown(GitTaskRepositoryException)
    }

    def "FR25: recordOutcome commits the matching message and content for each outcome variant"() {
        given:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then: 'the outcome-recording commit is the tip and carries the expected message'
        gitOutput(bareDir, 'log', '-1', "--format=%s", refFor('PROJ-1'))
                == ServiceCommitMessages.taskEvent(expectedEvent)

        and: 'task.json at the tip shows the recorded outcome type — Completed included, since its cleanup is a separate step (FR10 of harden-task-branch-contract)'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
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

    def "FR25: recordOutcome for Escalated populates lastEscalation and the tracker-write pending marker"() {
        given:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))

        then:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        content.lastEscalation() == report
        content.trackerWritePending()
    }

    def "FR25: recordOutcome for Aborted leaves the tracker-write pending marker unset, needs no environment"() {
        given:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        when: 'aborting records on the last harvested tip, factory-side, with no environment'
        repository.recordOutcome(
                'PROJ-1',
                new TaskOutcome.Aborted(TaskState.atStageStart('implement'),
                new AttemptKey('PROJ-1', 'implement', 0), 'boom'))

        then:
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        !content.trackerWritePending()
        content.outcome() instanceof RecordedOutcome.Aborted
    }

    // FR10 of harden-task-branch-contract: the cleanup commit is the destructive last step of the
    // completion sequence, so recordOutcome leaves the envelope in place for the tracker write to
    // follow — the container twin of the host repository's ordering.
    def "FR10: recordOutcome(Completed) leaves the envelope at the tip for finishCleanup to remove"() {
        given:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))

        then:
        gitOutput(bareDir, 'ls-tree', refFor('PROJ-1'), '--', '.gnomish-task') != ''
        gitOutput(bareDir, 'log', '-1', '--format=%s', refFor('PROJ-1'))
                == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.COMPLETED)
    }

    // FR10 of harden-task-branch-contract: the park's receipt, container-side.
    def "FR10: confirmTerminalWrite clears the pending marker a park recorded"() {
        given:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))

        when:
        repository.confirmTerminalWrite('PROJ-1')

        then: 'the marker is cleared while the recorded park and its report are preserved verbatim'
        def content = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1')))
        !content.trackerWritePending()
        content.outcome() instanceof RecordedOutcome.Escalated
        content.lastEscalation() == report
    }

    // FR10 of harden-task-branch-contract: running the destructive step twice equals running it once.
    def "FR10: finishCleanup on an already-cleaned tip changes nothing"() {
        given:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('PROJ-1')
        def tip = gitOutput(bareDir, 'rev-parse', refFor('PROJ-1'))

        when:
        repository.finishCleanup('PROJ-1')

        then:
        gitOutput(bareDir, 'rev-parse', refFor('PROJ-1')) == tip
    }

    def "FR25/M4: finishCleanup adds the cleanup commit removing .gnomish-task/ from the tip, history preserved"() {
        given: 'a task with an extra lifecycle commit before completion, to prove earlier history stays reachable'
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))
        repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'operator', null), TaskState.atStageStart('implement'))
        def commitsBefore = commitCount()

        when:
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('PROJ-1')

        then: 'the tip carries no .gnomish-task/ in its tree'
        gitOutput(bareDir, 'ls-tree', refFor('PROJ-1'), '--', '.gnomish-task') == ''

        and: 'the tip is the cleanup commit and its parent is the COMPLETED outcome commit'
        gitOutput(bareDir, 'log', '-1', '--format=%s', refFor('PROJ-1')) == ServiceCommitMessages.cleanup()
        gitOutput(bareDir, 'log', '-1', '--format=%s', "${refFor('PROJ-1')}~1") ==
                ServiceCommitMessages.taskEvent(TaskLifecycleEvent.COMPLETED)

        and: 'the completed task.json is still readable from the parent commit — history preserved'
        def historical = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(readTaskJson('PROJ-1', '~1')))
        historical.outcome() instanceof RecordedOutcome.Completed

        and: 'exactly two commits were appended (outcome + cleanup)'
        commitCount() == commitsBefore + 2
    }

    def "FR25/M4: non-Completed outcomes never add a cleanup commit, .gnomish-task/ stays at the tip"() {
        given:
        repository.createTask(sampleContext(), 'base', TaskState.atStageStart('implement'))

        when:
        repository.recordOutcome('PROJ-1', outcome)

        then:
        gitOutput(bareDir, 'ls-tree', refFor('PROJ-1'), '--', '.gnomish-task') != ''
        allCommitMessages().every { it != ServiceCommitMessages.cleanup() }

        where:
        outcome << [
            new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement'),
            new TaskOutcome.Escalated(TaskState.atStageStart('implement'),
            new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])),
            new TaskOutcome.Aborted(TaskState.atStageStart('implement'),
            new AttemptKey('PROJ-1', 'implement', 0), 'boom'),
        ]
    }

    private int commitCount() {
        gitOutput(bareDir, 'rev-list', '--count', refFor('PROJ-1')) as int
    }

    private List<String> allCommitMessages() {
        gitOutput(bareDir, 'log', '--format=%s', refFor('PROJ-1')).readLines()
    }
}
