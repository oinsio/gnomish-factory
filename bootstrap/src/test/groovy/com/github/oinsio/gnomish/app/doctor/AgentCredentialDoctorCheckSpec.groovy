package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.readings

import com.github.oinsio.gnomish.adapter.agent.AgentCredentials
import spock.lang.Specification

/**
 * FR1, NFR-S1 of add-doctor-command: when a stage is container-bound, an agent credential must
 * reach an agent round through the round's own allowlist; the line names the present credentials,
 * never a value, or the names to set.
 */
class AgentCredentialDoctorCheckSpec extends Specification {

    def "FR1, NFR-S1: a credential that reaches the round passes, named without its value"() {
        given:
        def check = new AgentCredentialDoctorCheck(readings([CLAUDE_CODE_OAUTH_TOKEN: 'oauth-value']))

        when:
        def verdicts = check.run(DoctorSpecSupport.bound(true, false))

        then:
        check.id() == DoctorCheckId.AGENT_CREDENTIAL
        verdicts == [
            new DoctorVerdict(DoctorCheckId.AGENT_CREDENTIAL, DoctorStatus.OK, 'CLAUDE_CODE_OAUTH_TOKEN present', null)
        ]
        !verdicts.toString().contains('oauth-value')
    }

    def "FR1: no credential fails, naming the credentials to set"() {
        expect:
        new AgentCredentialDoctorCheck(readings()).run(DoctorSpecSupport.bound(true, false)) == [
            new DoctorVerdict(DoctorCheckId.AGENT_CREDENTIAL, DoctorStatus.FAIL, 'no agent credential reaches an agent round',
            'set one of ' + AgentCredentials.names().join(', '))
        ]
    }

    def "FR1: an allowlist the configuration refuses fails with its refusal"() {
        given: 'a passthrough name that is a declared credential'
        def check = new AgentCredentialDoctorCheck(readings([:], [passthrough: [
                DoctorSpecSupport.TRACKER_TOKEN
            ]]))

        when:
        def verdicts = check.run(DoctorSpecSupport.bound(true, false, DoctorSpecSupport.definition(true)))

        then:
        verdicts*.status() == [DoctorStatus.FAIL]
        verdicts[0].detail().contains('factory.sandbox.env-passthrough must not name declared credential variables')
    }

    def "D3: no container-bound stage skips the check"() {
        expect:
        new AgentCredentialDoctorCheck(readings()).run(DoctorSpecSupport.bound(false, true))*.status() == [DoctorStatus.SKIPPED]
    }
}
