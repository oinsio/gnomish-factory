package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * FR15 of harden-logging-observability: {@link FindingsFileReader#read}'s three malformed-channel
 * degradations. Each one silently replaces the check's own findings with a synthetic one, so the
 * operator's only route from "the report says something odd" back to "the check wrote a bad
 * channel" is the WARN — which makes its catalog code, its level, and the check identity it names
 * the contract, and the sentence free to change.
 *
 * <p>Driven against the reader directly rather than through {@code ShellCommandCheckRunner}: the
 * three branches are content-shape decisions with no process in them, and a shell command that
 * writes each shape would add a subprocess per branch to assert nothing extra.
 */
class FindingsFileReaderSpec extends Specification {

    private static final String CHECK = 'unit-tests'

    private static byte[] bytes(String text) {
        text.getBytes(StandardCharsets.UTF_8)
    }

    def "a findings object with no findings array is a coded WARN naming the check, and no findings"() {
        given:
        def logs = LogCaptureSupport.attach(FindingsFileReader)

        when:
        def findings = FindingsFileReader.read(CHECK, bytes('{"other":[]}'))

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

    def "an entry with a blank message is a coded WARN naming the check, and no findings"() {
        given:
        def logs = LogCaptureSupport.attach(FindingsFileReader)

        when:
        def findings = FindingsFileReader.read(CHECK, bytes('{"findings":[{"message":"  "}]}'))

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
    }

    def "content that is not JSON at all is a coded WARN carrying the parse failure"() {
        given:
        def logs = LogCaptureSupport.attach(FindingsFileReader)

        when:
        def findings = FindingsFileReader.read(CHECK, bytes('not json at all'))

        then:
        findings == null

        and:
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FINDINGS_FILE_MALFORMED.head())
        }
        warned != null
        warned.level == Level.WARN
        warned.formattedMessage.contains(CHECK)
        warned.throwableProxy != null

        cleanup:
        logs.detach()
    }

    def "a well-formed channel yields its findings and warns about nothing"() {
        given:
        def logs = LogCaptureSupport.attach(FindingsFileReader)

        when:
        def findings = FindingsFileReader.read(CHECK, bytes('{"findings":[{"message":"a real finding"}]}'))

        then:
        findings*.message() == ['a real finding']
        logs.list.every { it.level != Level.WARN }

        cleanup:
        logs.detach()
    }
}
