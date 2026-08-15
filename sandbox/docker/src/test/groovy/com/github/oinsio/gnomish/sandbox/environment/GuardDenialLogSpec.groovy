package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * NFR-O1, NFR-C1, UX3 of add-sandbox-core: the guard's stdout parses into
 * structured denial findings — metadata only, host and path visible at a glance
 * — while unmarked lines, malformed events, and denial storms degrade safely
 * (skipped, dropped with a warning, truncated at the cap) rather than failing
 * anything.
 */
class GuardDenialLogSpec extends Specification {

    def "NFR-O1: a CONNECT denial parses into a finding with host and port"() {
        given:
        def log = 'GNOMISH-EGRESS-DENY {"kind":"connect","host":"evil.example.com","port":443}\n'

        when:
        def findings = GuardDenialLog.findings(log)

        then:
        findings.size() == 1
        findings[0].message() == 'egress denied: evil.example.com:443'
        findings[0].location() == 'evil.example.com:443'
        findings[0].details() == 'kind=connect'
    }

    def "UX3: a plain-HTTP denial carries method and path so the operator sees what was attempted"() {
        given:
        def log = 'GNOMISH-EGRESS-DENY {"kind":"http","host":"evil.example.com","port":80,' +
                '"method":"POST","path":"/exfil?data=secret"}\n'

        when:
        def findings = GuardDenialLog.findings(log)

        then:
        findings[0].message() == 'egress denied: evil.example.com:80'
        findings[0].location() == 'evil.example.com:80/exfil?data=secret'
        findings[0].details() == 'kind=http method=POST'
    }

    def "NFR-O1: a denial forwarded through mitmproxy's event log keeps its prefix and still parses"() {
        given: 'mitmproxy prepended its own timestamp/level prefix to the addon print line'
        def log = '[10:02:33.123][info] GNOMISH-EGRESS-DENY {"kind":"connect","host":"evil.example.com","port":443}\n'

        expect: 'the marker is matched anywhere in the line'
        GuardDenialLog.findings(log)*.message() == [
            'egress denied: evil.example.com:443'
        ]
    }

    def "NFR-O1: unmarked mitmdump output lines are not denials and are skipped"() {
        given:
        def log = [
            'Proxy server listening at http://*:8080',
            'GNOMISH-EGRESS-DENY {"kind":"connect","host":"a.example.com","port":443}',
            'some other noise line',
        ].join('\n')

        expect:
        GuardDenialLog.findings(log)*.message() == [
            'egress denied: a.example.com:443'
        ]
    }

    def "NFR-S3: a malformed marked line is dropped, never a failure"() {
        given:
        def log = [
            'GNOMISH-EGRESS-DENY not-json-at-all',
            'GNOMISH-EGRESS-DENY {"kind":"connect","port":443}',
            'GNOMISH-EGRESS-DENY {"kind":"connect","host":"ok.example.com","port":443}',
        ].join('\n')

        expect: 'the unparseable and host-less events vanish, the good one survives'
        GuardDenialLog.findings(log)*.message() == [
            'egress denied: ok.example.com:443'
        ]
    }

    def "NFR-C1: a denial storm is truncated at the event cap"() {
        given: 'more marked events than the cap'
        def log = (1..(GuardDenialLog.MAX_EVENTS + 50)).collect {
            "GNOMISH-EGRESS-DENY {\"kind\":\"connect\",\"host\":\"h${it}.example.com\",\"port\":443}"
        }.join('\n')

        expect:
        GuardDenialLog.findings(log).size() == GuardDenialLog.MAX_EVENTS
    }

    def "NFR-C1: oversized string fields are length-capped, not carried whole"() {
        given: 'a denial with a path far beyond the field cap'
        def hugePath = '/' + ('x' * 5000)
        def log = "GNOMISH-EGRESS-DENY {\"kind\":\"http\",\"host\":\"evil.example.com\",\"port\":80," +
                "\"method\":\"GET\",\"path\":\"${hugePath}\"}"

        when:
        def findings = GuardDenialLog.findings(log)

        then: 'the location stays bounded'
        findings[0].location().length() < 700
    }

    def "NFR-O1: an empty or denial-free log yields no findings"() {
        expect:
        GuardDenialLog.findings('') == []
        GuardDenialLog.findings('Proxy server listening at http://*:8080\n') == []
    }
}
