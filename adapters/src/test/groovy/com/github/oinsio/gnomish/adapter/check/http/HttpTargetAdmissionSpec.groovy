package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.httpegress.OperatorEgress
import spock.lang.Specification

/**
 * FR15, FR17, UX4 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09 (the
 * law-medium bullet): the load-time judgement of one {@code http} check's target against the
 * operator's {@code factory.egress.in-process} scope. A request nobody grants is a located error at
 * the check's {@code url}; a target whose host only a run can know, and a url the params validator
 * already reports, are left to connect. Load resolves no name.
 */
class HttpTargetAdmissionSpec extends Specification {

    private static final String FILE = 'stages/verify/stage.yaml'

    private static List judge(Map<String, Object> settings, List<String> inProcess) {
        HttpTargetAdmission.judge(FILE, 'verify[0].params', settings, new OperatorEgress(inProcess))
    }

    // FR17, UX4: the law requests, the operator grants — a located error naming how to grant it
    def "a literal target nobody grants is a located load error at the check's url"() {
        when:
        def errors = judge([url: 'https://sonar.example.com/api/qualitygates?projectKey=${task.id}'], ['ci.example.com'])

        then:
        errors.size() == 1
        errors[0].file() == FILE
        errors[0].where() == 'verify[0].params.url'
        errors[0].message().contains('missing allowlist entry')
        errors[0].message().contains("host 'sonar.example.com'")
        errors[0].message().contains('factory.egress.in-process entry naming the host')
        errors[0].message().contains("a 'connection:' profile grants only its own connection's calls")
    }

    def "a literal target the in-process scope grants loads: #why"() {
        expect:
        judge([url: url], inProcess) == []

        where:
        why | url | inProcess
        'by its exact host' | 'https://sonar.example.com/api' | ['sonar.example.com']
        'by a suffix' | 'https://a.ci.example.com/s' | ['*.ci.example.com']
        'with a reference outside the authority' | 'https://sonar.example.com/${task.id}?b=${task.branch}' | ['sonar.example.com']
    }

    // FR17, main spec "Plain http is refused": a manifest target is https only, decided at load
    def "a plain-http target is a located load error naming the scheme"() {
        when:
        def errors = judge([url: 'http://localhost:9000/api'], ['loopback'])

        then:
        errors*.where() == ['verify[0].params.url']
        errors[0].message().contains('(scheme, factory.egress.in-process)')
    }

    // D16 (item 11 of the third review): the host is the run's, so connect judges it
    def "a target whose authority carries a reference is not judged at load: #url"() {
        expect:
        judge([url: url], []) == []

        where:
        url << [
            'https://${task.id}.ci.example.com/status',
            'http://ci.example.com:${task.id}/status',
            'https://${task.branch}/status',
        ]
    }

    // One load problem, one error: the params validator reports these at the same place
    def "a url the params validator reports is left to it: #why"() {
        expect:
        judge(settings, []) == []

        where:
        why | settings
        'no url' | [:]
        'a url of another type' | [url: 42]
        'a malformed url' | [url: 'https://exa mple.com/']
        'a relative url' | [url: '/status']
    }
}
