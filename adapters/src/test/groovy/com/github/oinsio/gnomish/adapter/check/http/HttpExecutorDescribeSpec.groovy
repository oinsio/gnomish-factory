package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement
import com.github.oinsio.gnomish.executorcontract.SettingsSchema
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.Provenance
import spock.lang.Specification

/**
 * FR6, NFR-C1 of migrate-verify-to-executor-contract: the {@code http} built-in's describe — role
 * {@code verdict}, the {@code poll} capability, the settings schema of the former params, no
 * credential or egress of its own, usage reported — the runs it refuses, and the poll of a pending
 * run, which is the same request again. A setting the schema refuses the run refuses too, and a
 * setting the run accepts the schema accepts (design D4: the params validator becomes a schema).
 */
class HttpExecutorDescribeSpec extends Specification implements HttpCheckFixture {

    private HttpExecutor built(ScriptedExchange exchange = new ScriptedExchange(200, 'ok')) {
        executor(exchange, [:], HttpRunContext.none(), null)
    }

    def "FR6: the describe declares a polled verdict built-in with no credential or egress of its own"() {
        when:
        def describe = built().describe()

        then:
        HttpExecutor.NAME == 'http'
        describe.protocolVersion() == Describe.SPOKEN_PROTOCOL_VERSION
        describe.version().raw() == 'built-in'
        describe.roles() == [Role.VERDICT]
        describe.capabilities() == [Capability.POLL]
        describe.credentials().isEmpty()
        describe.egress().isEmpty()
        describe.configDirVariable() == null
        describe.policyDisablingFlags().isEmpty()
        describe.enforcementLevel() == EnforcementLevel.PROMPT_ONLY
        describe.reportsUsage()
        !describe.checkIdIsPinnedPath()
    }

    def "FR6: a setting the schema refuses, the run refuses before any request — #why"() {
        given:
        def exchange = new ScriptedExchange(200, 'ok')
        def schema = built().describe().settingsSchema()

        when:
        def judged = SettingsSchema.judge(schema, settings)
        def ran = built(exchange).start(verdict(settings))

        then: 'the preflight and the run agree, and nothing was sent'
        (judged instanceof SettingsJudgement.Accepted) == schemaAccepts
        ran.status() == Status.FAILED
        ran.failureClass() == FailureClass.INFRASTRUCTURE
        ran.reason().contains(reason)
        ran.reason().provenance() == Provenance.MANIFEST
        exchange.lastRequest == null

        where:
        why | settings || schemaAccepts | reason
        'no url' | [:] || false | 'break its schema: url (required)'
        'a url of another type' | [url: 42] || false | 'url (type)'
        'an unknown key' | [url: URL, verbose: true] || false | 'verbose (additionalProperties)'
        'headers that are no object' | [url: URL, headers: 'x'] || false | 'headers (type)'
        'a header holding a list' | [url: URL, headers: [a: ['x']]] || false | 'headers.a (type)'
        'an unknown auth key' | [url: URL, auth: [credential: 'T', user: 'u']] || false | 'auth.user'
        'an unknown condition key' | [url: URL, ('pass-when'): [jq: '.x']] || false | 'pass-when.jq'
        'a pending condition of another type' | [url: URL, ('pending-when'): 'x'] || false | 'pending-when (type)'
        'a relative url' | [url: '/status'] || true | 'settings are malformed: settings.url must be an absolute URL'
        'a method no check may send' | [url: URL, method: 'DELETE'] || true | "unknown method 'DELETE'"
        'an inline authorization header' | [url: URL, headers: [Authorization: 'x']] || true | 'settings.headers.Authorization'
        'a reference outside the allowlist' | [url: URL + '?k=${secret.x}'] || true | "interpolates '\${secret.x}'"
        'an extractor with nothing to compare' | [url: URL, ('pass-when'): [regex: 'x']] || true | "no 'equals'"
    }

    def "FR6: a setting the run accepts, the schema accepts — #why"() {
        expect:
        SettingsSchema.judge(built().describe().settingsSchema(), settings) instanceof SettingsJudgement.Accepted
        executor(new ScriptedExchange(200, 'ok'), [T: 'token'], HttpRunContext.none(), null)
        .start(verdict(settings)).status() == Status.COMPLETED

        where:
        why | settings
        'the url alone' | [url: URL]
        'every key' | [url: URL, method: 'post', headers: [Accept: 'application/json', 'X-Count': 5, 'X-On': true],
            auth: [credential: 'T', header: 'X-Api-Key', scheme: ''],
            ('pass-when'): [('json-path'): '$.s', regex: '(\\w+)', equals: 'OK'],
            ('pending-when'): [('json-path'): '$.s', equals: 'RUNNING']]
        'empty blocks' | [url: URL, headers: [:], ('pass-when'): [:]]
    }

    def "FR6: every describe hands out a schema of its own"() {
        given:
        def executor = built()

        expect:
        !executor.describe().settingsSchema().is(executor.describe().settingsSchema())
    }

    def "FR6: a pending run is polled by sending the same request again, whatever its key"() {
        given:
        def settings = [url: URL, ('pending-when'): [('json-path'): 'state', equals: 'RUNNING']]
        def exchange = new ScriptedExchange(200, '{"state":"RUNNING"}')
        def executor = built(exchange)

        when:
        def started = executor.start(verdict(settings))

        then:
        started.status() == Status.PENDING
        started.pendingKey() == HttpResults.PENDING

        when: 'the platform has finished by the poll'
        exchange.body = '{"state":"DONE"}'
        exchange.lastRequest = null
        def polled = executor.poll(verdict(settings), new PendingKey('any key at all'))

        then:
        polled.status() == Status.COMPLETED
        results(polled).isEmpty()
        exchange.lastRequest.uri().toString() == URL
    }

    def "NFR-C1: a run reports a known zero of model use, #why"() {
        when:
        def usage = built(exchange).start(verdict(settings)).usage()

        then:
        usage.provenance() == UsageProvenance.REPORTED
        usage.models().isEmpty()
        usage.tools().isEmpty()
        usage.wallMillis() == null

        where:
        why | exchange | settings
        'passing' | new ScriptedExchange(200, 'ok') | [url: URL]
        'failing' | new ScriptedExchange(500, 'no') | [url: URL]
        'unreachable' | new ScriptedExchange(new IOException('down')) | [url: URL]
        'pending' | new ScriptedExchange(200, 'R') | [url: URL, ('pending-when'): [regex: '(R)', equals: 'R']]
    }

    def "FR6: a run in another role is failed/infrastructure naming the role, and nothing is sent"() {
        given:
        def exchange = new ScriptedExchange(200, 'ok')
        def request = new ExecutorRequest(Role.TRANSFORM, new Workspace('.'), new Policy.WorkspaceWrite(),
                RoleInputs.transform('make it'), ExecutorContractJson.settingsTree([url: URL]), Bounds.none(), [], null)

        when:
        def result = built(exchange).start(request)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == 'http answers verdict runs only, and was asked for a transform run'
        result.reason().provenance() == Provenance.FACTORY
        exchange.lastRequest == null
    }
}
