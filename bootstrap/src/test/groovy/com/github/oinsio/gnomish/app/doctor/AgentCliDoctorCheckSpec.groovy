package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.CLONE
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.readings

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.subprocess.ExecutableVerdict
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR4 of add-doctor-command (design D4): when a stage is host-bound, the agent CLI binary is
 * asked of the launch owner's {@code assessExecutable} with {@code factory.agent-cli-binary}, the
 * clone as the working directory and the PATH a host agent round hands its child — never a PATH
 * the check reads itself.
 */
class AgentCliDoctorCheckSpec extends Specification {

    HostLaunchAssessment launch = Mock()
    def properties = new FactoryProperties(null, '/opt/agent/claude', null, null)

    def "FR1, FR4: a binary the launch owner resolves passes, asked with the round's PATH"() {
        given:
        def check = new AgentCliDoctorCheck(launch, readings([PATH: '/round/bin:/usr/bin']), properties)

        when:
        def verdicts = check.run(DoctorSpecSupport.bound(false, true))

        then:
        1 * launch.executable('/opt/agent/claude', CLONE.clonePath(), '/round/bin:/usr/bin') >>
                new ExecutableVerdict.Resolved(Path.of('/opt/agent/claude'))
        check.id() == DoctorCheckId.AGENT_CLI
        verdicts == [
            new DoctorVerdict(DoctorCheckId.AGENT_CLI, DoctorStatus.OK, '/opt/agent/claude resolves to /opt/agent/claude', null)
        ]
    }

    def "FR1: a binary that does not resolve fails with the owner's reason and the fix"() {
        given:
        launch.executable(_, _, null) >> new ExecutableVerdict.Unresolved('claude: not found on PATH')

        expect:
        new AgentCliDoctorCheck(launch, readings(), properties).run(DoctorSpecSupport.bound(false, true)) == [
            new DoctorVerdict(DoctorCheckId.AGENT_CLI, DoctorStatus.FAIL, 'claude: not found on PATH',
            'install the agent CLI on the PATH an agent round receives, or set factory.agent-cli-binary to its absolute path')
        ]
    }

    def "FR1, UX1: a round environment the configuration refuses is thrown on to the command, the owner not asked"() {
        when:
        new AgentCliDoctorCheck(launch, readings([:], [passthrough: [
                DoctorSpecSupport.TRACKER_TOKEN
            ]]), properties)
        .run(DoctorSpecSupport.bound(false, true, DoctorSpecSupport.definition(true)))

        then: 'DoctorCommand, the one owner of a check that could not run, renders it with the log-file fix'
        0 * launch._
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains('must not name declared credential variables')
    }

    def "D3: no host-bound stage skips the check without asking the owner"() {
        when:
        def verdicts = new AgentCliDoctorCheck(launch, readings(), properties).run(DoctorSpecSupport.bound(true, false))

        then:
        0 * launch._
        verdicts == [
            new DoctorVerdict(DoctorCheckId.AGENT_CLI, DoctorStatus.SKIPPED, 'no stage binds the host adapter', null)
        ]
    }
}
