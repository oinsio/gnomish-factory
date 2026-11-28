package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The credentials of one {@link AgentRoundRunner} round (NFR-S1 of extract-agent-round-runner): the
 * names the dialect's describe declares, granted through the run allowlist's grant, those that can be
 * granted only. The full launch is {@link AgentRoundRunnerSpec}'s.
 */
@Timeout(60)
class AgentRoundCredentialSpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)
    }

    // NFR-S1 of extract-agent-round-runner: an agent CLI authenticates by any one of the credentials
    //     its dialect declares, so a declared name with no value, or one the allowlist withholds, is
    //     left out of the round rather than failing it
    def "NFR-S1: a declared credential with no value, or withheld by the allowlist, is omitted and the round runs"() {
        given:
        fixture.dialect.credentials = [
            'ABSENT_KEY',
            'FAKE_API_KEY',
            'TRACKER_TOKEN'
        ]
        fixture.withheld = ['TRACKER_TOKEN']

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'done\n'))

        then:
        result.status() == Status.COMPLETED
        def environment = fixture.agentLaunches.first().environment()
        environment['FAKE_API_KEY'] == 'fake-secret'
        !environment.containsKey('ABSENT_KEY')
        !environment.containsKey('TRACKER_TOKEN')
    }
}
