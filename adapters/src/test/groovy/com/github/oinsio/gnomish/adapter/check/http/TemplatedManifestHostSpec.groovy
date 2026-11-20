package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.adapter.pipeline.ConnectionAdmission
import com.github.oinsio.gnomish.adapter.pipeline.GnomishTreeWriter
import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.httpegress.EgressAllowlist
import com.github.oinsio.gnomish.httpegress.GuardedHttpCheckExchange
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR17 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09 (the law-medium
 * bullet, item 11 of the third review): a manifest target whose authority carries a {@code ${…}}
 * reference loads unjudged, and the host a run substitutes is judged at connect against the same
 * {@code in-process} scope, before any connection. The manifest is loaded by the real loader, and
 * the loaded check runs through the {@code http} built-in executor over the guarded exchange; the
 * guard's name service is stubbed to a public address, so no lookup leaves the JVM.
 */
class TemplatedManifestHostSpec extends Specification implements HttpCheckFixture, GnomishTreeWriter {

    private static final OperatorEgress SCOPE = new OperatorEgress(['*.ci.example.com'])

    @TempDir
    Path root

    private VerifyCheck.Executor loadedCheck() {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write('pipeline.yaml', 'stages:\n  - verify\n')
        write('stages/verify/instructions.md', 'verify it\n')
        write('stages/verify/stage.yaml', '''\
purpose: verify the work
outputs:
  - id: report
executor:
  type: agent-cli
  model: some-model
instructions: stages/verify/instructions.md
verify:
  - type: external
    checkId: quality-gate
    provider: http
    params:
      url: https://${task.id}.ci.example.com/status
    interval: 5s
    timeout: 60s
advancement: auto
''')
        def outcome = PipelineLoader.loadConfiguration(root, [:], [http: new HttpCheckParamsValidator()],
        new ConnectionAdmission(ConnectionProfiles.none(), [:], SCOPE), [] as Set).outcome()
        assert outcome instanceof LoadOutcome.Loaded
        (outcome as LoadOutcome.Loaded).definition().stages()[0].verify()[0] as VerifyCheck.Executor
    }

    private static HttpExecutor executorFor(VerifyCheck.Executor check, ScriptedExchange platform, String taskId) {
        def guard = new GuardedHttpCheckExchange(platform, new EgressAllowlist(SCOPE.inProcess(), { host ->
            [
                InetAddress.ofLiteral('93.184.216.34')
            ]
        }, EgressAllowlist.IN_PROCESS_KEY, false))
        def runContext = { name ->
            Optional.ofNullable(['task.id': taskId][name])
        } as HttpRunContext
        new HttpExecutor(new HttpCheckProbe(guard, { name ->
            Optional.empty()
        }, runContext), check, null)
    }

    // Scenario: verification/factory-egress-allowlist / A templated manifest host is judged at connect
    def "a templated host loads unjudged, and the substituted host is judged at connect against the in-process scope"() {
        when: 'the manifest is loaded: the authority is the run\'s, so load does not judge it'
        def check = loadedCheck()

        then:
        check.settings().url == 'https://${task.id}.ci.example.com/status'

        when: 'a run substitutes a host the scope grants'
        def granted = new ScriptedExchange(200, 'ok')
        def passed = executorFor(check, granted, '42').start(verdict(check.settings()))

        then: 'the request went out to the substituted host'
        passed.status() == Status.COMPLETED
        results(passed).isEmpty()
        granted.lastRequest.uri().toString() == 'https://42.ci.example.com/status'

        when: 'a run substitutes a value that moves the host outside the scope'
        def outside = new ScriptedExchange(200, 'ok')
        def refused = executorFor(check, outside, 'evil.example.org/').start(verdict(check.settings()))

        then: 'the guard refused it before any connection, naming the missing grant and the scope'
        outside.lastRequest == null
        refused.status() == Status.FAILED
        refused.failureClass() == FailureClass.INFRASTRUCTURE
        refused.reason().contains("host 'evil.example.org'")
        refused.reason().contains(EgressAllowlist.IN_PROCESS_KEY)
        refused.reason().contains(EgressRefusal.Reason.NOT_ALLOWLISTED.label())
    }
}
