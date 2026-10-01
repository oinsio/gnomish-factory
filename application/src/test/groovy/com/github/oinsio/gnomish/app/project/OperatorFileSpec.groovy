package com.github.oinsio.gnomish.app.project

import com.github.oinsio.gnomish.app.UsageException
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.origin.OriginLookup
import org.springframework.boot.origin.TextResourceOrigin
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link OperatorFile}: an operator file read once through the caller's reader and parsed with
 * every property's origin naming the file and its line (design D3, NFR-O1, NFR-P1).
 *
 * <p>Implements FR5, NFR-O1, NFR-P1 of add-project-registry.
 */
class OperatorFileSpec extends Specification {

    @TempDir
    Path tmp

    // FR5: each configuration file is optional
    def "a missing file reads as empty, without calling the reader"() {
        given:
        def calls = 0

        expect:
        OperatorFile.read({
            calls++; 'x'
        } as OperatorFile.Reader, tmp.resolve('factory.yaml')) == ''
        calls == 0
    }

    def "an existing file is read through the reader"() {
        given:
        def file = Files.writeString(tmp.resolve('factory.yaml'), 'factory: {}\n')

        expect:
        OperatorFile.read(OperatorFile.FILESYSTEM, file) == 'factory: {}\n'
    }

    def "a read failure surfaces as an unchecked I/O failure"() {
        given:
        def file = Files.writeString(tmp.resolve('factory.yaml'), '')

        when:
        OperatorFile.read({
            throw new IOException('denied')
        } as OperatorFile.Reader, file)

        then:
        def failure = thrown(UncheckedIOException)
        failure.cause.message == 'denied'
    }

    // NFR-O1: the origin names the file and the line, and the file itself
    def "NFR-O1: every property's origin is the file and its line"() {
        given:
        def file = tmp.resolve('factory.yaml')

        when:
        def source = OperatorFile.parse(file, 'factory:\n  serve:\n    slots: 2\n').first()
        def origin = OriginLookup.getOrigin(source, 'factory.serve.slots') as TextResourceOrigin

        then:
        source.getProperty('factory.serve.slots') == 2
        origin.resource.description == file.toString()
        origin.resource.getFile() == file.toFile()
        origin.location.line + 1 == 3
    }

    def "text that is not YAML is refused naming the file"() {
        given:
        def file = tmp.resolve('factory.yaml')

        when:
        OperatorFile.parse(file, 'factory: [unclosed\n')

        then:
        def refused = thrown(UsageException)
        refused.message.startsWith("$file is not valid YAML: ")
    }
}
