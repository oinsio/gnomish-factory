package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * NFR-O1, NFR-C1, UX3 of add-sandbox-core: the guard's stdout parses into
 * structured denial findings — metadata only, host and path visible at a glance
 * — while unmarked lines, malformed events, and denial storms degrade safely
 * (skipped, dropped, truncated at the cap) rather than failing anything.
 *
 * <p>Dropping is not silent and not a flood (D4, FR5 of harden-logging-observability): one read
 * that loses many events costs one line, naming the environment key and the counts.
 */
class GuardDenialLogSpec extends Specification {

    private static final String KEY = 'gnomish-PROJ-9'

    def "NFR-O1: a CONNECT denial parses into a finding with host and port"() {
        given:
        def log = 'GNOMISH-EGRESS-DENY {"kind":"connect","host":"evil.example.com","port":443}\n'

        when:
        def findings = GuardDenialLog.findings(KEY, log)

        then:
        findings.size() == 1
        findings[0].message() == 'egress denied: evil.example.com:443'
        findings[0].location() == 'evil.example.com:443'
        findings[0].details() == 'kind=connect'
    }

    def "UX3: a plain-HTTP denial carries method and path so the operator sees what was attempted"() {
        given:
        def log = 'GNOMISH-EGRESS-DENY {"kind":"http","host":"evil.example.com","port":80,' +
                '"method":"POST","path":"/exfil"}\n'

        when:
        def findings = GuardDenialLog.findings(KEY, log)

        then:
        findings[0].message() == 'egress denied: evil.example.com:80'
        findings[0].location() == 'evil.example.com:80/exfil'
        findings[0].details() == 'kind=http method=POST'
    }

    // NFR-S1 of fix-denial-report-attachment: the query string is gnome-chosen payload, not
    //     destination metadata — the denied request's own exfiltration attempt must not ride the
    //     finding into the committed report, so the path is cut at the first '?'
    def "NFR-S1: the query string is stripped from the denied path"() {
        given: 'a denial whose query string carries the very data the guard blocked'
        def log = 'GNOMISH-EGRESS-DENY {"kind":"http","host":"evil.example.com","port":80,' +
                '"method":"GET","path":"/upload?token=s3cret&body=stolen"}\n'

        when:
        def findings = GuardDenialLog.findings(KEY, log)

        then: 'the operator still sees where the gnome went, never what it tried to send'
        findings[0].location() == 'evil.example.com:80/upload'
    }

    def "NFR-S1: a path that is nothing but a query string degrades to the destination alone"() {
        given:
        def log = 'GNOMISH-EGRESS-DENY {"kind":"http","host":"evil.example.com","port":80,' +
                '"method":"GET","path":"?token=s3cret"}\n'

        expect: 'stripping leaves an empty path, which reports as the bare destination'
        GuardDenialLog.findings(KEY, log)*.location() == ['evil.example.com:80']
    }

    def "NFR-O1: a denial forwarded through mitmproxy's event log keeps its prefix and still parses"() {
        given: 'mitmproxy prepended its own timestamp/level prefix to the addon print line'
        def log = '[10:02:33.123][info] GNOMISH-EGRESS-DENY {"kind":"connect","host":"evil.example.com","port":443}\n'

        expect: 'the marker is matched anywhere in the line'
        GuardDenialLog.findings(KEY, log)*.message() == [
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
        GuardDenialLog.findings(KEY, log)*.message() == [
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
        GuardDenialLog.findings(KEY, log)*.message() == [
            'egress denied: ok.example.com:443'
        ]
    }

    def "NFR-C1: a denial storm is truncated at the event cap"() {
        given: 'more marked events than the cap'
        def log = (1..(GuardDenialLog.MAX_EVENTS + 50)).collect {
            "GNOMISH-EGRESS-DENY {\"kind\":\"connect\",\"host\":\"h${it}.example.com\",\"port\":443}"
        }.join('\n')

        expect:
        GuardDenialLog.findings(KEY, log).size() == GuardDenialLog.MAX_EVENTS
    }

    def "NFR-C1: oversized string fields are length-capped, not carried whole"() {
        given: 'a denial with a path far beyond the field cap'
        def hugePath = '/' + ('x' * 5000)
        // No '?' in it: the field cap, not the query strip, is what has to bound this one
        def log = "GNOMISH-EGRESS-DENY {\"kind\":\"http\",\"host\":\"evil.example.com\",\"port\":80," +
                "\"method\":\"GET\",\"path\":\"${hugePath}\"}"

        when:
        def findings = GuardDenialLog.findings(KEY, log)

        then: 'the location stays bounded'
        findings[0].location().length() < 700
    }

    def "FR5, D4: a read that drops many events reports them once, with counts and the key"() {
        given: 'a read in which every marked line is unparseable, in both ways'
        def logs = LogCaptureSupport.attach(GuardDenialLog)
        def log = ((1..20).collect { 'GNOMISH-EGRESS-DENY {not json' }
        + (1..5).collect {
            'GNOMISH-EGRESS-DENY {"kind":"connect","port":443}'
        }).join('\n')

        when:
        def findings = GuardDenialLog.findings(KEY, log)

        then: 'nothing was parseable, and nothing was silently lost either'
        findings.isEmpty()

        and: 'one line for the whole read — not twenty-five'
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('dropped 25 unparseable guard denial event(s)')
        warnings[0].formattedMessage.contains('20 malformed, 5 without a host')
        warnings[0].formattedMessage.contains(KEY)

        cleanup:
        logs.detach()
    }

    // FR5, D4: the aggregate's three remaining edges — equal counts of the two drop kinds still
    // report (the counts are added, never differenced), the FIRST malformed reason is the one that
    // rides along, and a read that lost only host-less events says so instead of naming a reason
    // it never saw.
    def "FR5, D4: one malformed and one host-less event still report as two drops"() {
        given:
        def logs = LogCaptureSupport.attach(GuardDenialLog)
        def log = [
            'GNOMISH-EGRESS-DENY {not json',
            'GNOMISH-EGRESS-DENY {"kind":"connect","port":443}'
        ].join('\n')

        when:
        GuardDenialLog.findings(KEY, log)

        then:
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('dropped 2 unparseable guard denial event(s)')
        warnings[0].formattedMessage.contains('1 malformed, 1 without a host')

        cleanup:
        logs.detach()
    }

    def "FR5, D4: the first malformed reason is the one that rides along, not the last"() {
        given: 'two unparseable lines whose parser messages differ in the character they name'
        def logs = LogCaptureSupport.attach(GuardDenialLog)
        def log = [
            'GNOMISH-EGRESS-DENY {oops',
            'GNOMISH-EGRESS-DENY @@@'
        ].join('\n')

        when:
        GuardDenialLog.findings(KEY, log)

        then:
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('2 malformed, 0 without a host')

        and: 'the reason quoted is the first line\'s, so the aggregate points at where it started'
        def reason = warnings[0].formattedMessage.split('First malformed reason: ')[1]
        reason.contains("'o'")
        !reason.contains('@')

        cleanup:
        logs.detach()
    }

    def "FR5, D4: a read that lost only host-less events names no malformed reason"() {
        given:
        def logs = LogCaptureSupport.attach(GuardDenialLog)

        when:
        GuardDenialLog.findings(KEY, 'GNOMISH-EGRESS-DENY {"kind":"connect","port":443}')

        then:
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('0 malformed, 1 without a host')
        warnings[0].formattedMessage.contains('First malformed reason: none')

        cleanup:
        logs.detach()
    }

    def "FR5: a read that loses nothing says nothing"() {
        given:
        def logs = LogCaptureSupport.attach(GuardDenialLog)

        when:
        GuardDenialLog.findings(KEY, 'GNOMISH-EGRESS-DENY {"kind":"connect","host":"a.example.com","port":443}')

        then:
        logs.list.isEmpty()

        cleanup:
        logs.detach()
    }

    def "NFR-C1: the truncation line names the environment whose guard flooded"() {
        given:
        def logs = LogCaptureSupport.attach(GuardDenialLog)
        def log = (1..(GuardDenialLog.MAX_EVENTS + 5)).collect {
            "GNOMISH-EGRESS-DENY {\"kind\":\"connect\",\"host\":\"h${it}.example.com\",\"port\":443}"
        }.join('\n')

        when:
        GuardDenialLog.findings(KEY, log)

        then:
        logs.list.find { it.level == Level.WARN }.formattedMessage.contains(KEY)

        cleanup:
        logs.detach()
    }

    def "NFR-O1: an empty or denial-free log yields no findings"() {
        expect:
        GuardDenialLog.findings(KEY, '') == []
        GuardDenialLog.findings(KEY, 'Proxy server listening at http://*:8080\n') == []
    }
}
