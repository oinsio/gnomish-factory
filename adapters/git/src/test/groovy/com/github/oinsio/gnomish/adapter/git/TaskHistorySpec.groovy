package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.MalformedStateFileException
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR19 of define-executor-contract (design D13): a task's history is the range from the {@code
 * baseCommit} its {@code task.json} recorded to its registered commit, and the task id its records
 * must name — both read at the register's envelope revision, the base refused unless it is a full
 * object id, since it becomes a revision argument. FR18 (design D15): the {@code totals} the tip
 * records are read at the same revision, from {@code state.json}, apart from the walked rounds.
 */
class TaskHistorySpec extends Specification {

    private static final Path CLONE = Path.of('/clone')
    private static final RegisteredCommit REGISTERED = new RegisteredCommit('a' * 40)

    private static String taskJson(String baseCommit) {
        '{"version":1,"taskId":"PROJ-1","title":"T","body":"B","createdAt":"2026-07-18T09:00:00Z",' +
                '"baseCommit":"' + baseCommit + '","decisions":[]}'
    }

    /** A snapshot no fold of any round list could produce: the figure exactly as written. */
    private static final UsageSnapshot WRITTEN = new UsageSnapshot(
    [(ExecutorRole.VERDICT): ['model-j': new TokenUsage(7, 8, 9, 10)]],
    new UsageCost(new BigDecimal('0.25'), [:], true), 41, RoundToken.of('d' * 40))

    private static String stateJson(UsageSnapshot totals) {
        def state = new TaskState(new Position.AtStage('build'), 0, [], totals, ExecutorVersions.none())
        TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state))
    }

    private static ScriptedGit envelope(String baseCommit) {
        new ScriptedGit()
                .answer([
                    'show',
                    '*:.gnomish-task/task.json'
                ], 0, taskJson(baseCommit))
                .answer([
                    'show',
                    '*:.gnomish-task/state.json'
                ], 0, stateJson(WRITTEN))
    }

    def "FR19: the range runs from the recorded baseCommit to the registered commit, for the recorded task"() {
        when:
        def history = TaskHistory.of(envelope('b' * 40), CLONE, REGISTERED)

        then:
        history.taskId() == 'PROJ-1'
        history.revisions() == 'b' * 40 + '..' + 'a' * 40
    }

    def "FR18: the recorded totals are the tip's state.json totals, exactly as written"() {
        when:
        def history = TaskHistory.of(envelope('b' * 40), CLONE, REGISTERED)

        then:
        history.recordedTotals() == WRITTEN
    }

    def "FR18: an envelope revision carrying task.json but no state.json has no recorded totals to read"() {
        given:
        def git = new ScriptedGit()
                .answer([
                    'show',
                    '*:.gnomish-task/task.json'
                ], 0, taskJson('b' * 40))
                .answer([
                    'show',
                    '*:.gnomish-task/state.json'
                ], 128, '', 'fatal: path does not exist')

        when:
        TaskHistory.of(git, CLONE, REGISTERED)

        then:
        def missing = thrown(BranchStateFileMissingException)
        missing.message.contains('state.json')
    }

    def "FR19: a baseCommit that is not a full object id is refused before git sees it"() {
        given:
        def git = new ScriptedGit().answer([
            'show',
            '*:.gnomish-task/task.json'
        ], 0, taskJson(base))

        when:
        TaskHistory.of(git, CLONE, REGISTERED)

        then:
        thrown(MalformedStateFileException)

        where:
        base << [
            '--output=/tmp/x',
            'b' * 39,
            'main'
        ]
    }

    def "FR19: a registered commit with no envelope that is not the cleanup commit has no history to read"() {
        given:
        def git = new ScriptedGit()
                .answer(['show'], 128, '', 'fatal: path does not exist')
                .answer(['log'], 0, 'gnomish: round 0 of build\n')

        when:
        TaskHistory.of(git, CLONE, REGISTERED)

        then:
        thrown(BranchStateFileMissingException)
    }

    def "FR19: a registered cleanup whose Completed commit carries no task.json has no history to read"() {
        given:
        def completed = 'c' * 40
        def git = new ScriptedGit()
                .answer(['show'], 128, '', 'fatal: path does not exist')
                .answer(['log'], 0, ServiceCommitMessages.cleanup() + '\n')
                .answer(['rev-parse'], 0, completed + '\n')

        when:
        TaskHistory.of(git, CLONE, REGISTERED)

        then:
        def missing = thrown(BranchStateFileMissingException)
        missing.message.contains(completed)
    }
}
