package com.github.oinsio.gnomish.executorcontract

import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The text read of the one result-file reader (FR6, NFR-R1, NFR-S2 of define-executor-contract):
 * absent against empty, the cap with its truncation marker, inert decoding, the channel's family,
 * and the read's idempotence.
 */
class ResultFileTextReadSpec extends Specification {

    def reader = new ResultFileReader(16, UntrustedText.&agent)

    def "FR6: no file is absent, and an empty file is present text, never absent"() {
        expect:
        reader.readText(CannedSource.absent()) == new ReadResult.Absent()
        reader.readText(CannedSource.of('')) == new ReadResult.Parsed(UntrustedText.agent(''))
    }

    def "FR6: the reader asks its source for one byte more than the cap, and nothing more"() {
        given:
        def source = CannedSource.of('x' * 100)

        when:
        reader.readText(source)

        then:
        source.limits == [17L]
    }

    def "FR6: a file of exactly the cap is whole"() {
        expect:
        reader.readText(CannedSource.of('a' * 16)) == new ReadResult.Parsed(UntrustedText.agent('a' * 16))
    }

    // Scenario: executor/result-channel / Oversized result is truncated, not fatal to the factory
    def "FR6: a file one byte over the cap is cut at the cap, recorded as truncated and marked"() {
        when:
        def result = reader.readText(CannedSource.of('b' * 17))

        then:
        result == new ReadResult.Truncated(16, new ReadResult.Parsed(UntrustedText.agent('b' * 16 + reader.truncationMarker())))
    }

    def "FR6: the truncation marker names the cap it cut at"() {
        expect:
        new ResultFileReader(1024, UntrustedText.&agent).truncationMarker() ==
                '\n[truncated: the file is longer than the read cap of 1024 bytes]'
    }

    def "NFR-S2: the text is minted in the channel's family"() {
        given:
        def container = new ResultFileReader(16, UntrustedText.&container)

        expect:
        (container.readText(CannedSource.of('ok')) as ReadResult.Parsed).value().provenance() == Provenance.CONTAINER
    }

    def "NFR-S2: bytes are inert: malformed UTF-8 is replaced, control characters are kept as data"() {
        given:
        byte[] bytes = [
            0x61,
            0xFF,
            0x1B,
            0x5B,
            0x32,
            0x4A,
            0x00
        ] as byte[]

        when:
        def text = (reader.readText(CannedSource.ofBytes(bytes)) as ReadResult.Parsed).value() as UntrustedText

        then: 'the raw text is the decoded bytes, the bad byte replaced'
        text == UntrustedText.agent('a�\u001B[2J\u0000')

        and: 'it leaves only through an exit that neutralizes it'
        !text.forLog().contains('\u001B')
    }

    // NFR-R1: recovery re-reads, so a read must change nothing a second read would see
    def "NFR-R1: reading the same file twice yields equal results"() {
        given:
        def source = CannedSource.of('c' * 40)

        expect:
        reader.readText(source) == reader.readText(source)
    }

    def "FR6: a cap outside 1 to MAX_CAP_BYTES is refused"() {
        when:
        new ResultFileReader(cap, UntrustedText.&agent)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "a result-file cap is 1 to ${ResultFileReader.MAX_CAP_BYTES} bytes, got ${cap}"

        where:
        cap << [
            0,
            -1,
            ResultFileReader.MAX_CAP_BYTES + 1
        ]
    }

    def "FR6: the bounds of the cap are accepted"() {
        expect:
        new ResultFileReader(cap, UntrustedText.&agent).truncationMarker().contains(" ${cap} bytes")

        where:
        cap << [
            1,
            ResultFileReader.MAX_CAP_BYTES
        ]
    }

    def "FR6: the channel caps are the ones the reads carry today"() {
        expect:
        ResultFileReader.DECISION_FILE_CAP_BYTES == 1_048_576
        ResultFileReader.FINDINGS_FILE_CAP_BYTES == 262_144
        ResultFileReader.MAX_CAP_BYTES == 16_777_216
    }
}
