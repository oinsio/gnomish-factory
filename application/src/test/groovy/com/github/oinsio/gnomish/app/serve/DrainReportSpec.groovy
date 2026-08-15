package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * DrainReport: the thread-safe sink drain mode's slots record their terminal outcome into (FR10,
 * NFR-O2), and the human-readable closing summary {@code ServeCommand} logs once a drain run
 * finishes.
 *
 * Implements FR10, NFR-O2, M3 of add-factory-serve.
 */
class DrainReportSpec extends Specification {

    // M3: "--drain on an empty queue exits 0 with an empty-run report" — a report nothing was
    //     ever recorded into must still summarize sensibly, not as an empty string or an error.
    def "an empty report summarizes zero tasks worked"() {
        expect:
        new DrainReport().summary() == 'drain worked 0 task(s)'
    }

    // FR10, NFR-O2: recorded outcomes are named in the summary, one per worked task.
    def "records a task's outcome and names it in the summary"() {
        given:
        def report = new DrainReport()
        def ref = new TaskRef('github:o/r#1')

        when:
        report.record(ref, new TakeResult.Delivered(TaskState.atStageStart('build'), 'shipped it'))

        then:
        report.entries().size() == 1
        report.summary().contains('github:o/r#1')
        report.summary().contains('delivered')
        report.summary() == 'drain worked 1 task(s): github:o/r#1 -> delivered: shipped it'
    }

    // FR10, NFR-O2: several worked tasks are all named, in the order they finished.
    def "summarizes multiple recorded outcomes"() {
        given:
        def report = new DrainReport()

        when:
        report.record(new TaskRef('github:o/r#1'), new TakeResult.Delivered(TaskState.atStageStart('build'), 'done'))
        report.record(
                new TaskRef('github:o/r#2'),
                new TakeResult.AwaitingHuman(TaskState.atStageStart('build'), ParkReason.ESCALATION, 'needs a human'))

        then:
        report.entries().size() == 2
        report.summary() == 'drain worked 2 task(s): github:o/r#1 -> delivered: done, ' +
                'github:o/r#2 -> parked (ESCALATION): needs a human'
    }

    // FR10: the report is the sink several concurrently-finishing slots write into — no lost
    //     writes and no corruption under concurrent record() calls.
    def "records from concurrent slots without losing any entry"() {
        given:
        def report = new DrainReport()
        def taskCount = 20
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        def done = new CountDownLatch(taskCount)

        when:
        (0..<taskCount).each { i ->
            executor.submit {
                report.record(new TaskRef("github:o/r#${i}" as String), new TakeResult.Delivered(TaskState.atStageStart('build'), 'ok'))
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        executor.close()

        then:
        report.entries().size() == taskCount
    }
}
