package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R1 of define-executor-contract: a recovery that reads a finished {@code program} run's result
 * and answer files again — after a factory restart, from the environment they were written in —
 * reads the same status, answer and usage the first read did, and starts nothing: the read never
 * deletes or rewrites a file and runs no process.
 */
class ProgramResultReReadSpec extends Specification {

    static final String RESULT = StandIn.data('executor-result#completed')

    static final String ANSWER = '{"kind":"decision-request","question":"Which?","options":["a","b"]}'

    @TempDir
    Path stderrParent

    // Scenario: executor/result-channel / Re-read is idempotent
    // NFR-R1: recovery re-reads the same result files, never re-runs the round
    def "NFR-R1: re-reading a finished run's result and answer files yields the same status, answer and usage, and runs nothing"() {
        given: 'the files a finished run left in its environment'
        def environment = Mock(TaskExecutionEnvironment)
        def stderr = RoundScratchDirectory.open(stderrParent, RoundScratchDirectory.Writer.PROGRAM)
        def context = new ProgramResultRead.FailureContext(ProgramFixtures.NAME, stderr)
        def exited = new ProgramEnd.Exited(0, Duration.ofSeconds(2))

        when: 'the first pickup reads them, and a recovery after a restart reads them again'
        def first = ProgramResultRead.read(environment, '/s/result.json', '/s/answer.json', exited, context)
        def again = ProgramResultRead.read(environment, '/s/result.json', '/s/answer.json', exited, context)

        then: 'each read reads the files and nothing else: no process is started, nothing is written'
        2 * environment.readFile('/s/result.json', _) >> Optional.of(RESULT.getBytes(StandardCharsets.UTF_8))
        2 * environment.readFile('/s/answer.json', _) >> Optional.of(ANSWER.getBytes(StandardCharsets.UTF_8))
        0 * environment._

        and: 'the second read is the first'
        again == first
        first.status() == Status.COMPLETED
        first.answer().get('question').textValue() == 'Which?'
        first.usage().provenance() == UsageProvenance.REPORTED
        first.usage().models().keySet() == ['acme-model-1'] as Set

        cleanup:
        stderr?.close()
    }
}
