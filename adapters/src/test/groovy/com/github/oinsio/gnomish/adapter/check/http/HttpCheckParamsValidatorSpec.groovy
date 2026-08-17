package com.github.oinsio.gnomish.adapter.check.http

import spock.lang.Specification

/**
 * FR6, FR10, NFR-S1 of add-plugin-architecture: the http provider's counterpart of the github
 * params validator grades a check's target, auth and conditions at the load seam, so a malformed
 * http check is a located ConfigError aggregated with every other load problem — never an adapter
 * failure discovered mid-stage.
 */
class HttpCheckParamsValidatorSpec extends Specification implements HttpCheckFixture {

    private static final String FILE = 'stages/verify/stage.yaml'
    private static final String WHERE = 'verify[0].params'

    private List<String> problems(Map<String, Object> params) {
        new HttpCheckParamsValidator().validate(FILE, WHERE, params).collect {
            "${it.where()}: ${it.message()}" as String
        }
    }

    def "a minimal well-formed check is accepted"() {
        expect:
        problems([url: URL]).isEmpty()
    }

    def "a fully declared check is accepted"() {
        given:
        def params = [
            url: URL,
            method: 'POST',
            headers: [Accept: 'application/json'],
            auth: [credential: 'TOKEN', header: 'X-Api-Key', scheme: ''],
            ('pass-when'): [('json-path'): '$.status', regex: '(\\w+)', equals: 'OK'],
            ('pending-when'): [('json-path'): '$.status', equals: 'PENDING']
        ]

        expect:
        problems(params).isEmpty()
    }

    // FR6: the one required param — nothing else supplies a base for it.
    def "the url is required, absolute and well-formed"() {
        expect:
        problems(params).any {
            it.startsWith('verify[0].params.url') && it.contains(fragment)
        }

        where:
        params || fragment
        [:] || "requires a non-blank 'url'"
        [url: '  '] || "requires a non-blank 'url'"
        [url: '/relative/path'] || 'must be an absolute URL'
        [url: 'https://a b.invalid'] || 'is not a valid URL'
    }

    // FR6: a params block written for another provider must not pass quietly here either.
    def "an unknown param is a located error naming it"() {
        expect:
        problems([url: URL, repo: 'acme/widgets']).any {
            it.startsWith('verify[0].params.repo') && it.contains("unknown param 'repo'")
        }
    }

    def "the method must be one of the read-shaped verbs"() {
        expect:
        problems([url: URL, method: 'DELETE']).any {
            it.contains("unknown method 'DELETE'")
        }
        problems([url: URL, method: 'head']).isEmpty()
    }

    // NFR-S1: the manifest is committed, so a literal authorization header would commit a secret.
    def "an inline authorization header is refused in favour of a named credential"() {
        expect:
        problems([url: URL, headers: [Authorization: 'Bearer hunter2']]).any {
            it.startsWith('verify[0].params.headers.Authorization') && it.contains("under 'auth'")
        }
    }

    def "auth requires a non-blank credential name and rejects unknown keys"() {
        expect:
        problems([url: URL, auth: [header: 'X-Api-Key']]).any {
            it.startsWith('verify[0].params.auth.credential')
        }
        problems([url: URL, auth: [credential: 'TOKEN', value: 'hunter2']]).any {
            it.startsWith('verify[0].params.auth.value') && it.contains("unknown key 'value'")
        }
    }

    // FR10: an extractor and its equals only mean something together.
    def "a condition's extractor and equals must be declared together"() {
        expect:
        problems([url: URL, ('pass-when'): [('json-path'): '$.status']]).any {
            it.contains("declares an extractor but no 'equals'")
        }
        problems([url: URL, ('pass-when'): [equals: 'OK']]).any {
            it.contains("declares 'equals' but neither")
        }
    }

    // FR10: a pending_when asserting nothing would match every response and poll to the timeout.
    def "pending_when must declare an extractor"() {
        expect:
        problems([url: URL, ('pending-when'): [:]]).isEmpty()
        problems([url: URL, ('pending-when'): [equals: 'PENDING']]).any {
            it.contains('poll until the check times out')
        }
    }

    def "a condition rejects unknown and non-string keys"() {
        expect:
        problems([url: URL, ('pass-when'): [status: 200]]).any {
            it.startsWith('verify[0].params.pass-when.status') && it.contains("unknown key 'status'") &&
            it.contains('[equals, json-path, regex]')
        }
        problems([url: URL, ('pass-when'): [('json-path'): '  ', equals: 'OK']]).any {
            it.contains("'json-path' must be a non-blank string")
        }
    }

    // FR10: a pattern that cannot compile would never match, reading as a permanently failing check.
    def "an uncompilable regex is a located error"() {
        expect:
        problems([url: URL, ('pass-when'): [regex: '([unclosed', equals: 'x']]).any {
            it.startsWith('verify[0].params.pass-when.regex') && it.contains('not a valid regular expression')
        }
    }

    // NFR-S2, D5: the egress allowlist is operator-owned — a manifest that writes one is an unknown
    //     param, so it cannot widen what the operator permitted.
    def "a manifest cannot declare an allowlist of its own"() {
        expect:
        problems([url: URL, allowlist: ['evil.example.net']]).any {
            it.startsWith('verify[0].params.allowlist') && it.contains("unknown param 'allowlist'")
        }
    }

    // NFR-S2, D5: interpolation is restricted to the fixed engine-defined whitelist — a manifest
    //     cannot smuggle a secret or attacker-controlled value into a URL or a header.
    def "a non-whitelisted interpolation is a located error naming the variable"() {
        expect:
        problems([url: 'https://ci.example.invalid/${env.SONAR_TOKEN}']) == [
            'verify[0].params.url: interpolates \'${env.SONAR_TOKEN}\', which is not an interpolatable'
            + ' variable; allowed: [attempt.commit, stage.name, task.branch, task.id]'
        ]
    }

    def "a non-whitelisted interpolation in a header is located at that header"() {
        expect:
        problems([url: URL, headers: ['X-Leak': '${secrets.token}']]).any {
            it.startsWith('verify[0].params.headers.X-Leak') && it.contains('secrets.token')
        }
    }

    // NFR-S2: the four whitelisted variables pass — including through the url's syntax check, which
    //     grades the shape the request will take rather than the one the manifest wrote.
    def "the whitelisted variables are accepted in the url and in headers"() {
        expect:
        problems([url: 'https://ci.example.invalid/${task.branch}/${attempt.commit}?s=${stage.name}&t=${task.id}',
            headers: ['X-Stage': '${stage.name}']]).isEmpty()
    }
}
