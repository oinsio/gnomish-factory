package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9 of make-checkpoint-gate-durable: the container-medium {@code recordOutcome} decides the
 * denial-cursor read from the report of the outcome being recorded, never from the
 * {@code lastEscalation} a later {@code Paused}/{@code Completed}/{@code Aborted} write carries over
 * from the tip for display. The cursor cases of the escalation write itself stay in
 * {@link GitObjectsTaskRepositorySpec}.
 */
class GitObjectsRecordOutcomeCursorSpec extends Specification implements BareGitRepoFixture {

    private static final BasePin PIN = new BasePin('base', BaseRefKind.BRANCH, BaseRule.EXPLICIT_ARGUMENT)

    private static final EgressCursorDto COMMITTED = new EgressCursorDto('sha256:guard', '2026-09-05T10:05:00Z')

    @TempDir
    Path tempDir

    Path bareDir

    def setup() {
        Path work = initWorkingRepo(tempDir, 'seed-work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work, 'init')
        bareDir = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bareDir.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
    }

    // FR9: a carried-over cannotExecute is display history, not a drain this write performed — so
    //     the write asks the environment nothing and the committed position stands
    def "FR9: a #kind write after a tip carrying cannotExecute reads no cursor and preserves the committed one"() {
        given: 'a tip whose task.json carries a cannotExecute escalation and the position its drain left'
        def parking = repositoryReading({
            Optional.of(new DenialCursor(COMMITTED.source(), COMMITTED.position()))
        })
        parking.createTask(new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(bareDir, 'refs/heads/base'), PIN, TaskState.atStageStart('implement'))
        parking.recordOutcome('PROJ-1', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), cannotExecute()),
                TrackerWrite.OWED)

        and: 'an environment that would answer a newer position, counting every ask'
        int asked = 0
        def later = repositoryReading({
            asked++
            Optional.of(new DenialCursor('sha256:guard', '2026-09-05T11:00:00Z'))
        })
        def logs = LogCaptureSupport.attach(LifecycleEgressCursor)

        when:
        later.recordOutcome('PROJ-1', outcome, TrackerWrite.OWED)
        def events = List.copyOf(logs.list)

        then: 'the environment was not asked, and the committed position is carried forward unchanged'
        asked == 0
        def dto = TaskJsonMapper.readDto(UntrustedText.branchDocument(
                        gitOutput(bareDir, 'show', 'refs/heads/gnomish/PROJ-1:.gnomish-task/task.json')))
        dto.egressCursor() == COMMITTED

        and: 'the carried escalation is still there for display, and no cursor warning was logged'
        dto.lastEscalation().denials().size() == 1
        events.empty

        cleanup:
        logs.detach()

        where:
        kind | outcome
        'Paused' | new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement')
        'Completed' | new TaskOutcome.Completed(TaskState.atStageStart('implement'))
        'Aborted' | new TaskOutcome.Aborted(TaskState.atStageStart('implement'),
                new AttemptKey('PROJ-1', 'implement', 0), UntrustedText.factory('guard violated'))
    }

    private static EscalationReport cannotExecute() {
        new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out'), [
            Denial.unidentified(new Finding('egress denied: paste.example.com:443', 'paste.example.com:443/upload', null))
        ])
    }

    /** A repository whose cursor-reading writes ask {@code source}. */
    private GitObjectsTaskRepository repositoryReading(DenialCursorSource source) {
        new GitObjectsTaskRepository(
                GitObjects.open(bareDir, Files.createDirectories(tempDir.resolve('index-' + System.identityHashCode(source)))),
                new CommitIdentity('gnomish-factory', 'gnomish-factory@localhost'),
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC),
                ClaimEpochSource.NONE,
                source)
    }
}
