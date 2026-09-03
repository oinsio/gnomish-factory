package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.LogCallSites
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * The scanner behind both convention gates of harden-logging-observability (D9): FR6's
 * {@link UntrustedLogTextGateSpec} and FR7's {@link ThrowableConventionGateSpec} judge only the
 * call sites this scanner hands them, so a site it drops is a site neither gate checks — a green
 * run that means nothing for that line.
 *
 * <p>Two rules keep it fail-closed: comment stripping never cuts inside a string literal (the
 * first URL in a log message would otherwise unbalance the call), and a site whose argument list
 * still cannot be delimited is reported rather than skipped.
 */
class LogCallScannerSpec extends Specification {

    // The realistic shape: a log message naming a URL. A blind `//` strip cuts the literal open,
    // the call never closes, and the site silently leaves both gates.
    def "a log call whose message contains // inside a literal is still scanned: #label"() {
        given:
        def code = RepoSourceTree.codeOnly(line)

        when:
        def scan = LogCallSites.scan(code, 'Seeded.java')

        then: 'the site is delimited, not dropped'
        scan.unparsed == []
        scan.calls.size() == 1
        scan.calls[0].text.contains(argument)

        where:
        label | line || argument
        'url in the message' |
                'log.warn("poll of https://host/api failed: {}", LogText.forLog(result.stderr()));' ||
                'result.stderr()'
        'url in a trailing argument' |
                'log.info("fetching {}", "https://host/api");' || 'https://host/api'
    }

    def "a real trailing comment is still stripped"() {
        expect:
        RepoSourceTree.codeOnly('log.info("done"); // nothing to see') == 'log.info("done"); '
    }

    def "a site whose argument list cannot be delimited is reported, never silently skipped"() {
        given: 'a call the parser cannot close — the shape a stripping artifact leaves behind'
        def code = 'log.warn("unterminated'

        when:
        def scan = LogCallSites.scan(code, 'Broken.java')

        then:
        scan.calls == []
        scan.unparsed == ['Broken.java:1']
    }

    def "a fluent chain with no terminal log call is reported too"() {
        given:
        def code = 'log.atWarn().setMessage("boom");'

        when:
        def scan = LogCallSites.scan(code, 'Fluent.java')

        then:
        scan.calls == []
        scan.unparsed == ['Fluent.java:1']
    }
}
