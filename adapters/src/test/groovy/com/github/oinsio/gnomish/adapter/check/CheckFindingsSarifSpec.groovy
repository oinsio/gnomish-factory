package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.check.SarifResultReading
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR5 of migrate-verify-to-executor-contract: {@link CheckFindings} reads a SARIF log on the findings
 * channel through the contract's own SARIF reading, and bounds a legacy file by the results one
 * verdict may carry. A refusal of either is a coded WARN naming the check, and no findings — the
 * caller's synthetic result. Driven against a stub environment, as {@code CheckFindingsSpec} is.
 */
class CheckFindingsSarifSpec extends Specification {

    private static final String CHECK = 'unit-tests'

    private static final String PATH = '/scratch/findings.json'

    private TaskExecutionEnvironment holding(String content) {
        def bytes = content.getBytes(StandardCharsets.UTF_8)
        Stub(TaskExecutionEnvironment) {
            readFile(PATH, _ as Long) >> { String path, long cap ->
                Optional.of(Arrays.copyOf(bytes, (int) Math.min(cap, bytes.length)))
            }
        }
    }

    private List<ShellResult> findingsOf(String content) {
        CheckFindings.results(CHECK, CheckFindings.read(holding(content), PATH))
    }

    def "FR5: a SARIF log is relayed result by result, every level kept"() {
        given:
        def logs = LogCaptureSupport.attach(CheckFindings)
        def log = '{"version":"2.1.0","runs":[' +
                '{"tool":{"driver":{"name":"a"}},"results":[{"ruleId":"r1","level":"note","message":{"text":"n"}}]},' +
                '{"tool":{"driver":{"name":"b"}},"results":[{"message":{"text":"w"},"locations":[{"physicalLocation":' +
                '{"artifactLocation":{"uri":"src/B.java"}}}]}]}]}'

        when:
        def results = findingsOf(log)

        then: 'both runs in the order written, a level-less result read as a warning, no details'
        results*.sarif()*.level() == [
            SarifLevel.NOTE,
            SarifLevel.WARNING
        ]
        results*.sarif()*.ruleId()*.forLog() == ['r1', null]
        results.collect { SarifResultReading.toDomain(it.asSarif()) } == [
            new VerdictAnswer.Result(ResultLevel.NOTE, new Finding('n', null, null)),
            new VerdictAnswer.Result(ResultLevel.WARNING, new Finding('w', 'src/B.java', null))
        ]
        logs.list.every { it.level != Level.WARN }

        cleanup:
        logs.detach()
    }

    // Scenario: verification/verdict-executors / A malformed findings file is logged
    def "FR5: a SARIF log the contract refuses is a coded WARN carrying its reason, and no findings"() {
        given:
        def logs = LogCaptureSupport.attach(CheckFindings)

        when:
        def findings = findingsOf(content)

        then:
        findings.isEmpty()
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FINDINGS_FILE_MALFORMED.head())
        }
        warned != null
        warned.level == Level.WARN
        warned.formattedMessage.contains(CHECK)
        warned.formattedMessage.contains(reason)

        cleanup:
        logs.detach()

        where:
        content || reason
        '{"version":"2.0.0","runs":[]}' || "version '2.0.0'"
        '{"runs":[]}' || 'version'
        '{"version":"2.1.0"}' || 'runs'
    }

    def "FR5: more legacy findings than one verdict may carry are a coded WARN, and no findings"() {
        given:
        def logs = LogCaptureSupport.attach(CheckFindings)
        def entries = (1..(Answer.Verdict.MAX_RESULTS + 1)).collect {
            '{"message":"m"}'
        }.join(',')

        when:
        def findings = findingsOf('{"findings":[' + entries + ']}')

        then:
        findings.isEmpty()
        logs.list.any {
            it.level == Level.WARN &&
            it.formattedMessage.startsWith(OperatorEvent.FINDINGS_FILE_MALFORMED.head()) &&
            it.formattedMessage.contains("${Answer.Verdict.MAX_RESULTS + 1} findings")
        }

        and: 'exactly the cap is still read'
        findingsOf('{"findings":[' + entries.substring('{"message":"m"},'.length()) + ']}').size() ==
                Answer.Verdict.MAX_RESULTS

        cleanup:
        logs.detach()
    }
}
