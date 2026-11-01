package com.github.oinsio.gnomish.executorcontract

import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The typed read of the one result-file reader (FR6, NFR-R1, NFR-S2 of define-executor-contract):
 * binding through the contract's one JSON binding, every failure a {@code Malformed} that keeps the
 * text, truncation resolved by the cut content's own parse outcome, carriers in the channel's
 * family, and a re-read equal to the first read.
 */
class ResultFileJsonReadSpec extends Specification {

    def reader = new ResultFileReader(ResultFileReader.DECISION_FILE_CAP_BYTES, UntrustedText.&agent)

    def "FR6: a result document binds to its DTO"() {
        when:
        def result = reader.readJson(CannedSource.of('{"status":"completed","usage":{"provenance":"ABSENT"}}'),
                ExecutorResult)

        then:
        result instanceof ReadResult.Parsed
        (result.value() as ExecutorResult).status() == Status.COMPLETED
    }

    def "FR6: no file is absent"() {
        expect:
        reader.readJson(CannedSource.absent(), ExecutorResult) == new ReadResult.Absent()
    }

    def "FR6: #what is malformed, keeping the whole text read"() {
        when:
        def result = reader.readJson(CannedSource.of(text), QuestionWire)

        then:
        result instanceof ReadResult.Malformed
        result.content() == UntrustedText.agent(text)
        !result.reason().blank

        where:
        what | text
        'an empty file' | ''
        'text that is not JSON' | 'please pick: A or B?'
        'a document with trailing content' | '{"question":"q"} and more'
        'a document missing a required field' | '{"options":["a","b"]}'
        'a document its constructor refuses' | '{"question":"q","options":["only"]}'
        'a carrier field that is not a string' | '{"question":{"nested":true}}'
    }

    def "FR6: the reason of a malformed read is the parser's, minted in the channel's family"() {
        when:
        def result = reader.readJson(CannedSource.of('not json'), QuestionWire) as ReadResult.Malformed

        then:
        result.reason().provenance() == Provenance.AGENT
        result.reason().raw().contains('not')
    }

    def "FR6: a JSON null document is malformed in the factory's words"() {
        when:
        def result = reader.readJson(CannedSource.of('null'), QuestionWire)

        then:
        result == new ReadResult.Malformed(
                UntrustedText.factory('the document is JSON null, not QuestionWire'), UntrustedText.agent('null'))
    }

    def "NFR-S2: every carrier field is minted in the channel's family"() {
        given:
        def container = new ResultFileReader(1024, UntrustedText.&container)

        when:
        def wire = (container.readJson(CannedSource.of('{"question":"q?","options":["a","b"],"note":"n"}'),
                QuestionWire) as ReadResult.Parsed).value() as QuestionWire

        then:
        wire == new QuestionWire(UntrustedText.container('q?'),
                [
                    UntrustedText.container('a'),
                    UntrustedText.container('b')
                ], UntrustedText.container('n'))
        ([wire.question(), wire.note()] + wire.options())*.provenance().every {
            it == Provenance.CONTAINER
        }
    }

    // Scenario: executor/result-channel / Oversized result is truncated, not fatal to the factory
    def "FR6: an oversized document is read to the cap and resolves per the cut content's parse outcome"() {
        given:
        def small = new ResultFileReader(cap, UntrustedText.&agent)
        def text = '{"question":"why?"}'

        when:
        def result = small.readJson(CannedSource.of(text), QuestionWire)

        then:
        result instanceof ReadResult.Truncated
        result.capBytes() == cap
        outcome.isInstance(result.content())

        where:
        cap | outcome
        5 | ReadResult.Malformed
        18 | ReadResult.Malformed
    }

    def "FR6: the content of a cut, malformed document is the cut text with the marker"() {
        given:
        def small = new ResultFileReader(5, UntrustedText.&agent)

        when:
        def result = small.readJson(CannedSource.of('{"question":"why?"}'), QuestionWire) as ReadResult.Truncated

        then:
        (result.content() as ReadResult.Malformed).content() == UntrustedText.agent('{"que' + small.truncationMarker())
    }

    def "FR6: a cut that falls in trailing whitespace still parses the document whole"() {
        given:
        def small = new ResultFileReader(16, UntrustedText.&agent)

        expect:
        small.readJson(CannedSource.of('{"question":"q"}' + ' ' * 10), QuestionWire) ==
                new ReadResult.Truncated(16,
                new ReadResult.Parsed(new QuestionWire(UntrustedText.agent('q'), null, null)))
    }

    // NFR-R1: recovery after a restart decides from the same file exactly as the first pickup did
    def "NFR-R1: reading the same result twice yields the same status, answer and usage"() {
        given:
        def source = CannedSource.of('{"status":"pending","pendingKey":"k-1","usage":{"provenance":"REPORTED",'
                + '"models":{"m":{"input":1,"output":2,"cacheCreation":0,"cacheRead":0}}}}')

        when:
        def first = (reader.readJson(source, ExecutorResult) as ReadResult.Parsed).value() as ExecutorResult
        def second = (reader.readJson(source, ExecutorResult) as ReadResult.Parsed).value() as ExecutorResult

        then:
        first == second
        first.status() == Status.PENDING
        first.usage().provenance() == UsageProvenance.REPORTED
    }
}
