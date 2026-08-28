package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16 of harden-task-branch-contract: a historical commit whose {@code state.json} cannot be read
 * is skipped with a warning naming the commit, and the walk carries on — the report renders from
 * the readable history instead of failing on the one broken commit in the middle of it.
 */
class UsageHistoryUnreadableCommitSpec extends Specification implements UsageHistoryFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupUsageHistoryFixture()
    }

    private static List<ILoggingEvent> capture(Closure<?> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(UsageHistoryWalker)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    private String commitGarbageState(String taskId) {
        def worktree = worktreeFor(taskId)
        new File(worktree.toFile(), '.gnomish-task/state.json').text = '{ this is not json'
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'corrupt mid-history')
        return runner.run(worktree, 'rev-parse', 'HEAD').stdout().trim()
    }

    def "FR16: an unreadable mid-history commit is skipped with a warning naming it, and the walk still renders"() {
        given: 'a first readable round'
        taskRepository().createTask(new TaskContext('PROJ-20', 'T', 'B', []), null, TaskState.atStageStart('implement'))
        def first = round(0, AttemptRecord.Result.QUALITY_FAILURE, 500, 50)
        def afterFirst = TaskState.atStageStart('implement').recordQualityFailure(first)
        persistRound('PROJ-20', afterFirst, 'implement', 0)

        and: 'a mid-history commit whose state.json is unparseable'
        def brokenCommit = commitGarbageState('PROJ-20')

        and: 'a later readable round, written over the broken file'
        def second = round(1, AttemptRecord.Result.PASSED, 700, 70)
        persistRound('PROJ-20', afterFirst.recordUnburnedRound(second), 'implement', 1)

        when:
        UsageHistoryResult result = null
        def events = capture { result = walker.walk(cloneDir, 'PROJ-20') }

        then: 'both readable rounds are reported — the broken commit cost only its own row'
        def found = result as UsageHistoryResult.Found
        found.rows().size() == 2
        found.rows()*.attempt()*.round() == [0, 1]

        and: 'the totals are computed over the readable history'
        found.totals() != null

        and: 'the skipped commit is named in a warning'
        def warning = events.find { it.level.toString() == 'WARN' }
        warning != null
        warning.formattedMessage.contains(brokenCommit)
        warning.formattedMessage.contains('state.json')
    }

    def "FR16: an unsupported state.json version in history is skipped too, not a thrown refusal"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-21', 'T', 'B', []), null, TaskState.atStageStart('implement'))
        def first = round(0, AttemptRecord.Result.PASSED, 100, 10)
        persistRound('PROJ-21', TaskState.atStageStart('implement').recordUnburnedRound(first), 'implement', 0)

        and: 'a mid-history commit declaring a state-file version this factory does not support'
        def worktree = worktreeFor('PROJ-21')
        def stateFile = new File(worktree.toFile(), '.gnomish-task/state.json')
        stateFile.text = stateFile.text.replaceFirst(/"version"\s*:\s*1/, '"version":2')
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'version 2')

        and: 'a later readable round'
        def second = round(1, AttemptRecord.Result.PASSED, 200, 20)
        persistRound('PROJ-21', TaskState.atStageStart('implement')
                .recordUnburnedRound(first).recordUnburnedRound(second), 'implement', 1)

        when:
        def result = walker.walk(cloneDir, 'PROJ-21')

        then:
        noExceptionThrown()
        (result as UsageHistoryResult.Found).rows()*.attempt()*.round() == [0, 1]
    }
}
