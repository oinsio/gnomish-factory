package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory
import com.github.oinsio.gnomish.executorcontract.PendingState
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR5 of migrate-verify-to-executor-contract (design D19): reading a {@code program} run's
 * result completes it — the measured wall time, the answer file — without dropping what the result
 * file said beside its status: the wait hint, the pending state and the details survive both
 * completions.
 */
class ProgramResultFactsSpec extends Specification {

    static final String PENDING = '{"status":"pending","pendingKey":"k","pollAfterMillis":5000,' +
    '"pendingState":"unobserved","details":"the tail","usage":{"provenance":"ABSENT"}}'

    static final String COMPLETED = '{"status":"completed","details":"the tail","usage":{"provenance":"ABSENT"}}'

    static final String SELECT = '{"kind":"select","chosen":"a"}'

    @TempDir
    Path stderrParent

    def "FR2, FR5: a #status result keeps its hint, state and details through the wall time and the answer file"() {
        given:
        def environment = Stub(TaskExecutionEnvironment) {
            readFile('/s/result.json', _) >> Optional.of(written.getBytes(StandardCharsets.UTF_8))
            readFile('/s/answer.json', _) >> Optional.ofNullable(answer?.getBytes(StandardCharsets.UTF_8))
        }
        def stderr = RoundScratchDirectory.open(stderrParent, RoundScratchDirectory.Writer.PROGRAM)
        def context = new ProgramResultRead.FailureContext(ProgramFixtures.NAME, stderr)

        when:
        def result = ProgramResultRead.read(environment, '/s/result.json', '/s/answer.json',
                new ProgramEnd.Exited(0, Duration.ofSeconds(2)), context)

        then:
        result.status() == status
        result.pollAfterMillis() == hint
        result.pendingState() == state
        result.details().forLog() == 'the tail'
        result.usage().wallMillis() == 2000L
        (result.answer() != null) == (answer != null)

        cleanup:
        stderr?.close()

        where:
        status | written | answer || hint | state
        Status.PENDING | PENDING | null || 5000L | PendingState.UNOBSERVED
        Status.COMPLETED | COMPLETED | SELECT || null | null
    }
}
