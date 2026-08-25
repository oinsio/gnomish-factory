package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.DenialCursor
import spock.lang.Specification

/**
 * FR3, FR8, D5, D9 of add-sandbox-core: the per-task construction seam for
 * guarded container environments, verified without a daemon — the per-role
 * environment wiring, the restored denial cursor reaching each role's guard
 * (FR5 of fix-denial-report-attachment), the round key exposed for bookkeeping,
 * and disposeExisting's full teardown of the round key's objects. The docker
 * availability probe moved out with its class ({@code DockerRuntimeProbeSpec}).
 */
class ContainerEnvironmentsSpec extends Specification implements ContainerEnvironmentsFixture {

    static final String KEY = 'org-repo-7'

    // FR8, D9: the judge role gets a real self-checked container environment, never a null seam
    def "judgeEnvironment builds a self-checked container environment"() {
        when:
        def judge = environments(KEY).judgeEnvironment()

        then: 'a non-null decorator over the container adapter, guard attached'
        judge != null
        judge.passport() == CapabilityPassport.container()

        and: 'the denial read reaches this role\'s own guard container (FR1 of fix-denial-report-attachment)'
        judge.denialFindings() == []
        docker.runs.last() == GuardCommands.guardLogs(KEY + '-j', 1000, null)
    }

    // FR3, FR8: the round role gets a real self-checked container environment, never a null seam
    def "roundEnvironment builds a self-checked container environment"() {
        when:
        def round = environments(KEY).roundEnvironment()

        then:
        round != null
        round.passport() == CapabilityPassport.container()

        and:
        round.denialFindings() == []
        docker.runs.last() == GuardCommands.guardLogs(KEY, 1000, null)
    }

    // FR13: the fresh verify-in: fresh-box role gets a real self-checked container environment,
    // never a null seam
    def "verificationEnvironment builds a self-checked container environment"() {
        when:
        def verification = environments(KEY).verificationEnvironment()

        then:
        verification != null
        verification.passport() == CapabilityPassport.container()

        and:
        verification.denialFindings() == []
        docker.runs.last() == GuardCommands.guardLogs(KEY + '-v', 1000, null)
    }

    // FR5 of fix-denial-report-attachment: a resume hands the run the cursor its last attempt
    // committed, and every environment built afterwards offers it to its own guard — so a round
    // box reattaching to the surviving guard container continues the delta instead of replaying it
    def "FR5: a restored cursor reaches the guard of every environment built afterwards"() {
        given: 'the guard container named by the committed cursor is the live one'
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardId(KEY) ? new DockerResult(0, 'sha256:container-1\n', '')
            : new DockerResult(0, '', '')
        }
        def seam = environments(KEY)

        when:
        seam.restoreDenialCursor(new DenialCursor('sha256:container-1', '2026-08-19T10:00:00.000000001Z'))
        seam.roundEnvironment().denialFindings()

        then: 'the round box reads its guard log from the committed position, not from the start'
        docker.runs.last() == GuardCommands.guardLogs(KEY, 1000, '2026-08-19T10:00:00.000000001Z')
    }

    // FR6: the round key is exposed verbatim for keep/dispose bookkeeping
    def "baseKey returns the round environment's sanitized key"() {
        expect:
        environments(KEY).baseKey() == KEY
    }

    // FR6, NFR-R2: disposeExisting removes container, guard, volume and network of the round key
    def "disposeExisting tears down every docker object of the round key"() {
        when:
        environments(KEY).disposeExisting()

        then:
        docker.runs == [
            DockerCommands.removeContainer('gnomish-box-' + KEY),
            GuardCommands.removeGuard(KEY),
            DockerCommands.removeVolume('gnomish-vol-' + KEY),
            DockerCommands.removeNetwork('gnomish-net-' + KEY),
        ]
    }
}
