package com.github.oinsio.gnomish.executorcontract

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The host result file (FR6, NFR-R1, NFR-S2 of define-executor-contract): a factory-created path
 * read without following a link, absent only when missing, and left in place by the read.
 */
class HostResultFileSpec extends Specification {

    @TempDir
    Path dir

    def "FR6: a missing file is absent"() {
        expect:
        ResultSource.hostFile(dir.resolve('decision.json')).read(10) == Optional.empty()
    }

    def "FR6: at most the limit is read from the start of the file"() {
        given:
        def file = Files.writeString(dir.resolve('decision.json'), 'abcdef')

        expect:
        new String(ResultSource.hostFile(file).read(limit).get(), 'UTF-8') == expected

        where:
        limit | expected
        4 | 'abcd'
        6 | 'abcdef'
        100 | 'abcdef'
    }

    // NFR-R1: a read never consumes the file; deleting a consumed file is the consumer's hygiene
    def "NFR-R1: reading leaves the file in place and a second read sees the same bytes"() {
        given:
        def file = Files.writeString(dir.resolve('result.json'), '{"status":"completed"}')
        def source = ResultSource.hostFile(file)

        when:
        def first = source.read(100).get()
        def second = source.read(100).get()

        then:
        first == second
        Files.readString(file) == '{"status":"completed"}'
    }

    def "NFR-S2: a symbolic link at the path is refused, never followed"() {
        given:
        def secret = Files.writeString(dir.resolve('secret'), 'credential')
        def link = Files.createSymbolicLink(dir.resolve('decision.json'), secret)

        when:
        ResultSource.hostFile(link).read(100)

        then:
        def e = thrown(UncheckedIOException)
        e.message == "could not read result file ${link}"
    }

    def "NFR-S2: a directory at the path is a failed read, not an absent file"() {
        given:
        def directory = Files.createDirectory(dir.resolve('decision.json'))

        when:
        ResultSource.hostFile(directory).read(100)

        then:
        thrown(UncheckedIOException)
    }
}
