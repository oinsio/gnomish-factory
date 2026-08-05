package com.github.oinsio.gnomish.serveobservability.json

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Contract spec for {@link LedgerLineReader}: proves the reader tolerates a
 * torn last ledger line (design D5, D8's crash model — only the currently
 * open tail line of a live append can be torn; earlier lines were already
 * flushed complete) rather than throwing, while a malformed line anywhere
 * else in the file is a genuine error.
 *
 * NFR-R2 (torn-tail tolerance) of add-serve-observability.
 */
class LedgerLineReaderSpec extends Specification {

    @TempDir
    Path tempDir

    def reader = new LedgerLineReader()

    def "a file of complete lines yields one record per line"() {
        given:
        def file = writeLines(tempDir, completeLines(3))

        when:
        def records = reader.read(file)

        then:
        records.size() == 3
    }

    def "a torn trailing line is skipped silently, yielding only the complete records"() {
        given:
        def file = writeRaw(tempDir, completeLines(3).join('\n') + '\n' + '{"version":1,"type":"taskOutcome","tas')

        when:
        def records = reader.read(file)

        then:
        records.size() == 3
    }

    def "a torn line that is NOT last is a genuine error"() {
        given:
        def broken = [
            '{"version":1,"type":"taskOutcome","tas'
        ] + completeLines(2)
        def file = writeRaw(tempDir, broken.join('\n') + '\n')

        when:
        reader.read(file)

        then: 'the 1-based line number in the message is correct (kills an i+1 -> i-1 mutant)'
        def failure = thrown(IOException)
        failure.message.contains('malformed ledger line 1 is not the last line')
    }

    def "a blank line in the middle of the file is skipped, not treated as an error"() {
        given:
        def file = writeRaw(tempDir, completeLines(2).join('\n') + '\n' + '\n' + completeLines(1)[0] + '\n')

        when:
        def records = reader.read(file)

        then:
        records.size() == 3
    }

    def "an empty file yields no records"() {
        given:
        def file = writeRaw(tempDir, '')

        when:
        def records = reader.read(file)

        then:
        records.isEmpty()
    }

    private static List<String> completeLines(int count) {
        (1..count).collect { i -> "{\"version\":1,\"type\":\"lifecycle\",\"seq\":${i}}" }
    }

    private static Path writeLines(Path dir, List<String> lines) {
        writeRaw(dir, lines.join('\n') + '\n')
    }

    private static Path writeRaw(Path dir, String content) {
        Path file = dir.resolve("ledger-under-test.jsonl")
        Files.writeString(file, content, StandardCharsets.UTF_8)
        file
    }
}
