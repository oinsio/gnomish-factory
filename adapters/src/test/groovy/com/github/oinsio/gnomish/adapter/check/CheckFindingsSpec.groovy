package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.executorcontract.ResultFileReader
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR15 of harden-logging-observability; FR6 of define-executor-contract: {@link CheckFindings} reads
 * the findings channel through the executor contract's result reader and degrades every
 * malformed shape to "no findings" (the caller's synthetic finding). Each degradation silently
 * replaces the check's own findings, so the operator's only route from "the report says something
 * odd" back to "the check wrote a bad channel" is the WARN — its catalog code, its level, and the
 * check identity it names are the contract, the sentence is free to change.
 *
 * <p>Driven against a stub environment rather than through {@code ShellCommandCheckRunner}: the
 * branches are content-shape decisions with no process in them.
 */
class CheckFindingsSpec extends Specification {

    private static final String CHECK = 'unit-tests'

    private static final String PATH = '/scratch/findings.json'

    /** An environment whose only file is {@code content} at {@link #PATH}, read with the cap asked. */
    private List<Long> asked = []

    private TaskExecutionEnvironment holding(String content) {
        def bytes = content == null ? null : content.getBytes(StandardCharsets.UTF_8)
        Stub(TaskExecutionEnvironment) {
            readFile(PATH, _ as Long) >> { String path, long cap ->
                asked << cap
                bytes == null ? Optional.empty() : Optional.of(Arrays.copyOf(bytes, (int) Math.min(cap, bytes.length)))
            }
        }
    }

    private List findingsOf(String content) {
        CheckFindings.findings(CHECK, CheckFindings.read(holding(content), PATH))
    }

    def "a findings object with no findings array is a coded WARN naming the check, and no findings"() {
        given:
        def logs = LogCaptureSupport.attach(CheckFindings)

        when:
        def findings = findingsOf('{"other":[]}')

        then: 'the caller falls back to a synthetic finding'
        findings == null

        and:
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FINDINGS_FILE_MISSING_ARRAY.head())
        }
        warned != null
        warned.level == Level.WARN
        warned.formattedMessage.contains(CHECK)

        cleanup:
        logs.detach()
    }

    def "an entry with a blank or missing message is a coded WARN naming the check, and no findings"() {
        given:
        def logs = LogCaptureSupport.attach(CheckFindings)

        when:
        def findings = findingsOf(content)

        then:
        findings == null

        and:
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FINDINGS_FILE_BLANK_ENTRY.head())
        }
        warned != null
        warned.level == Level.WARN
        warned.formattedMessage.contains(CHECK)

        cleanup:
        logs.detach()

        where:
        content << [
            '{"findings":[{"message":"  "}]}',
            '{"findings":[{"location":"a.txt"}]}',
            '{"findings":[null]}'
        ]
    }

    // Scenario: cli/manual-run / A malformed findings file is logged
    def "content that is not JSON at all is a coded WARN carrying the parser's reason"() {
        given:
        def logs = LogCaptureSupport.attach(CheckFindings)

        when:
        def findings = findingsOf('not json at all')

        then:
        findings == null

        and:
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FINDINGS_FILE_MALFORMED.head())
        }
        warned != null
        warned.level == Level.WARN
        warned.formattedMessage.contains(CHECK)
        warned.formattedMessage.contains("Unrecognized token 'not'")

        cleanup:
        logs.detach()
    }

    def "a well-formed channel yields its findings and warns about nothing"() {
        given:
        def logs = LogCaptureSupport.attach(CheckFindings)

        when:
        def findings = findingsOf('{"findings":[{"message":"a real finding","location":"a.txt:3","details":"d"}]}')

        then:
        findings*.message() == ['a real finding']
        findings*.location() == ['a.txt:3']
        findings*.details() == ['d']
        logs.list.every { it.level != Level.WARN }

        cleanup:
        logs.detach()
    }

    def "an absent, empty or blank channel is no findings and no warning"() {
        given:
        def logs = LogCaptureSupport.attach(CheckFindings)

        when:
        def findings = findingsOf(content)

        then:
        findings == null
        logs.list.every { it.level != Level.WARN }

        cleanup:
        logs.detach()

        where:
        content << [null, '', '  \n ']
    }

    // FR6 of define-executor-contract: the cap is the result reader's, asked of the environment.
    def "FR6: the channel is read at the findings cap, and a file cut there degrades to no findings"() {
        given: 'a findings file longer than the cap'
        def cap = ResultFileReader.FINDINGS_FILE_CAP_BYTES
        def long_ = '{"findings":[{"message":"' + ('m' * cap) + '"}]}'
        def logs = LogCaptureSupport.attach(CheckFindings)

        when:
        def findings = findingsOf(long_)

        then: 'the environment was asked for the cap and the one byte that proves a cut'
        asked == [cap + 1L]

        and: 'the cut JSON is malformed: the caller falls back to its synthetic finding'
        findings == null
        logs.list.any {
            it.formattedMessage.startsWith(OperatorEvent.FINDINGS_FILE_MALFORMED.head())
        }

        cleanup:
        logs.detach()
    }

    def "content on a passing check is noted at DEBUG, and nothing is noted for an empty channel"() {
        given:
        def logs = LogCaptureSupport.attach(CheckFindings, Level.DEBUG)

        when:
        CheckFindings.noteIgnoredOnPass(CHECK, CheckFindings.read(holding(content), PATH))
        def noted = logs.list.any {
            it.formattedMessage.contains('has content but the command exited 0')
        }

        then:
        noted == expected

        cleanup:
        logs.detach()

        where:
        content | expected
        '{"findings":[]}' | true
        'not json' | true
        '{"x":"' + ('y' * 300_000) + '"}' | true
        '' | false
        '   ' | false
        null | false
    }
}
