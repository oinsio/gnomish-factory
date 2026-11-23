package com.github.oinsio.gnomish.app.port.agent

import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.ResultFileReader
import com.github.oinsio.gnomish.executorcontract.ResultSource
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The one read of an answer file (FR21, NFR-R5 of extract-agent-round-runner; design D15, part 4):
 * absent, a contract answer, or refused with its cause — never a question built from the text.
 */
class AnswerFileReadSpec extends Specification {

    static final String DECISION = '{"kind":"decision-request","question":"which db?","options":["pg","sqlite"]}'

    @TempDir
    Path tempDir

    /** A medium holding {@code text}, answering a read of {@code limit} bytes as a file would. */
    private static ResultSource holding(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8)
        return { long limit ->
            Optional.of(Arrays.copyOf(bytes, (int) Math.min(bytes.length, limit)))
        } as ResultSource
    }

    def "FR21: no file is absent"() {
        expect:
        AnswerFileRead.read({ long limit ->
            Optional.empty()
        } as ResultSource, UntrustedText.&agent) ==
        new AnswerFileReading.Absent()
    }

    def "FR21: a valid answer is the contract's answer, its texts in the writer's family"() {
        when:
        def reading = AnswerFileRead.read(holding(DECISION), UntrustedText.&agent)

        then:
        reading instanceof AnswerFileReading.Answered
        def asked = (reading as AnswerFileReading.Answered).answer() as Answer.DecisionRequest
        asked.question().forLog() == 'which db?'
        asked.question().provenance() == Provenance.AGENT
        asked.options()*.forLog() == ['pg', 'sqlite']
    }

    // Scenario: executor/result-channel / An unparseable answer file is refused, never a question
    def "FR21: #content is refused with its cause, and no decision request is built from the text"() {
        when:
        def reading = AnswerFileRead.read(holding(text), UntrustedText.&agent)

        then:
        reading instanceof AnswerFileReading.Refused
        def refused = reading as AnswerFileReading.Refused
        refused.cause().startsWith(cause)
        refused.written().provenance() == Provenance.AGENT
        refused.written().contains(kept)

        where:
        content | text || cause | kept
        'text that is not JSON' | 'not json, the agent rambled' || 'wrote a malformed answer file: ' | 'the agent rambled'
        'an empty file' | '' || 'wrote an empty answer file' | ''
        'a file of white space only' | ' \n\t\n' || 'wrote an empty answer file' | '\n'
        'a question without a kind' | '{"question":"which db?"}' || 'wrote an answer file that is no valid answer: ' | 'which db?'
        'a decision request with one option' | '{"kind":"decision-request","question":"q?","options":["only"]}' || 'wrote an answer file that is no valid answer: ' | 'only'
    }

    // Scenario: executor/agent-executor / Answer file read is capped
    def "FR21: a file over the cap is read up to the cap and refused as over it, never read as a request"() {
        given:
        def cap = ResultFileReader.ANSWER_FILE_CAP_BYTES
        def text = '{"kind":"decision-request","question":"' + ('q' * cap) + '","options":["a","b"]}'
        long asked = 0
        def source = { long limit ->
            asked = limit
            Optional.of(Arrays.copyOf(text.getBytes(StandardCharsets.UTF_8), (int) Math.min(text.length(), limit)))
        } as ResultSource

        when:
        def reading = AnswerFileRead.read(source, UntrustedText.&agent)

        then: 'one byte past the cap is asked, to learn the file is longer'
        asked == cap + 1L
        reading instanceof AnswerFileReading.Refused
        (reading as AnswerFileReading.Refused).cause() == "wrote an answer file over the cap of ${cap} bytes".toString()
    }

    def "FR21: a file over the cap whose cut still parses is refused, quoting what was read"() {
        given: 'a whole answer, padded past the cap'
        def cap = ResultFileReader.ANSWER_FILE_CAP_BYTES
        def text = DECISION + (' ' * cap)

        when:
        def reading = AnswerFileRead.read(holding(text), UntrustedText.&agent)

        then:
        reading instanceof AnswerFileReading.Refused
        (reading as AnswerFileReading.Refused).written().forLog().contains('which db?')
    }

    def "NFR-R5: the same bytes give the same reading, whichever medium hands them over"() {
        given:
        def environment = Mock(TaskExecutionEnvironment) {
            readFile('.gnomish-task/decisions/a.json', _ as Long) >> { String path, Long limit ->
                Optional.of(Arrays.copyOf(text.getBytes(StandardCharsets.UTF_8), (int) Math.min(text.length(), limit)))
            }
        }
        def host = Files.writeString(tempDir.resolve('answer.json'), text)

        expect:
        AnswerFileRead.in(environment, '.gnomish-task/decisions/a.json', UntrustedText.&agent) ==
                AnswerFileRead.read(holding(text), UntrustedText.&agent)
        AnswerFileRead.onHost(host, UntrustedText.&agent) == AnswerFileRead.read(holding(text), UntrustedText.&agent)

        where:
        text << [DECISION, 'not json']
    }
}
