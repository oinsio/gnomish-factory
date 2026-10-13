package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BranchStateResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.status.StatusReportReferenceFixture
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, FR5 of fix-denial-attribution-durability, FR5 of fix-denial-report-attachment: a denial
 * cursor is a resume position and nothing else. Two envelopes that differ only in their cursor
 * reconstruct the same task state, and the escalation-side cursor in {@code task.json} never
 * reaches the reconstructed record or the {@code status.json} rendering.
 */
class DenialCursorReconstructionSpec extends Specification implements BareGitRepoFixture, RegisteredBranchFixture {

    private static final String SOURCE = 'sha256:guard-container'
    private static final String POSITION = '2026-09-05T10:05:00.000000001Z'
    private static final BasePin PIN = new BasePin('base', BaseRefKind.BRANCH, BaseRule.EXPLICIT_ARGUMENT)

    @TempDir
    Path tempDir

    // FR5 of fix-denial-report-attachment: the cursor rides state.json beside the attempts; a
    //     reader rebuilding position, attempts and usage takes nothing from it.
    // Scenario: git/git-task-persistence / The cursor changes no reconstructed field
    def "FR5: two state files differing only in their cursor reconstruct the same position, attempts and usage"() {
        given: 'the reference state, written once with a cursor and once without'
        def state = StatusReportReferenceFixture.referenceTaskState()
        def withCursor = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state, new EgressCursorDto(SOURCE, POSITION)))
        def withoutCursor = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state, null))
        assert withCursor != withoutCursor

        when:
        def a = StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(withCursor)))
        def b = StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(withoutCursor)))

        then:
        a.position() == b.position()
        a.attempts() == b.attempts()
        a.attemptsUsed() == b.attemptsUsed()
        a.totals() == b.totals()
        a == b
    }

    // FR3 of fix-denial-attribution-durability: the escalation park records the drained position
    //     in task.json; the record and the report built from the branch are the same whether or not
    //     it is there, and the rendered status.json does not carry it.
    // Scenario: git/git-task-persistence / The escalation cursor stays out of the reconstructed state
    def "FR3: an escalation cursor in task.json reaches neither the reconstructed record nor status.json"() {
        given: 'two branches parked on the same cannotExecute escalation, one environment answering a cursor'
        def escalation = new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out'), [
            Denial.unidentified(new Finding('egress denied: paste.example.com:443', 'paste.example.com:443/upload', null))
        ])
        Path withCursor = parkedRepository('with', {
            Optional.of(new DenialCursor(SOURCE, POSITION))
        }, escalation)
        Path withoutCursor = parkedRepository('without', {
            Optional.empty()
        }, escalation)

        expect: 'the cursor really is on one branch and not on the other'
        TaskJsonMapper.readDto(taskJson(withCursor)).egressCursor() == new EgressCursorDto(SOURCE, POSITION)
        TaskJsonMapper.readDto(taskJson(withoutCursor)).egressCursor() == null

        and: 'the reconstructed records are equal'
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(taskJson(withCursor))) ==
                TaskJsonMapper.fromDto(TaskJsonMapper.readDto(taskJson(withoutCursor)))

        and: 'status.json rendered from each branch is byte-identical and names neither half of the cursor'
        def rendered = statusJson(withCursor)
        rendered == statusJson(withoutCursor)
        !rendered.contains(SOURCE)
        !rendered.contains(POSITION)
        !rendered.contains('Cursor')
    }

    /** One seed commit for both branches, so the records differ in nothing the cursor does not. */
    private Path seed

    private Path seed() {
        if (seed == null) {
            seed = initWorkingRepo(tempDir, 'seed')
            Files.writeString(seed.resolve('a.txt'), 'first')
            commitAll(seed, 'init')
        }
        seed
    }

    private Path parkedRepository(String name, DenialCursorSource cursors, EscalationReport escalation) {
        Path bare = initBareRepo(tempDir, "${name}.git")
        gitOutput(seed(), 'push', bare.toString(), 'HEAD:refs/heads/base')
        def repository = new GitObjectsTaskRepository(
                GitObjects.open(bare, Files.createDirectories(tempDir.resolve("index-${name}"))),
                new CommitIdentity('gnomish-factory', 'gnomish-factory@localhost'),
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC),
                ClaimEpochSource.NONE,
                cursors)
        repository.createTask(new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(bare, 'refs/heads/base'), PIN, TaskState.atStageStart('implement'))
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), escalation), TrackerWrite.OWED)
        registerTip(bare, 'PROJ-1')
        bare
    }

    private UntrustedText taskJson(Path bare) {
        UntrustedText.branchDocument(gitOutput(bare, 'show', 'refs/heads/gnomish/PROJ-1:.gnomish-task/task.json'))
    }

    private String statusJson(Path bare) {
        def result = new BranchStateReader(new GitProcessRunner(), VirtualTimeGitRetries.gitInfrastructure()).read(bare, 'PROJ-1')
        new StatusReportJsonMapper().serialize((result as BranchStateResult.Found).report())
    }
}
