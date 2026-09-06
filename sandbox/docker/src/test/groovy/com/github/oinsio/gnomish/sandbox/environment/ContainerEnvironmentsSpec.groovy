package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.DenialIdentity
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.sandbox.DenialRestoration
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
        judge.readDenials().denials() == []
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
        round.readDenials().denials() == []
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
        verification.readDenials().denials() == []
        docker.runs.last() == GuardCommands.guardLogs(KEY + '-v', 1000, null)
    }

    // FR5 of fix-denial-report-attachment: a resume hands the run the cursor its last attempt
    // committed, and the round environment offers it to its own guard — so a round box
    // reattaching to the surviving guard container continues the delta instead of replaying it
    def "FR5: a restored cursor reaches the guard of the round environment"() {
        given: 'the guard container named by the committed cursor is the live one'
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardId(KEY) ? new DockerResult(0, 'sha256:container-1\n', '')
            : new DockerResult(0, '', '')
        }
        def seam = environments(KEY)

        when:
        seam.restoreDenials(DenialRestoration.at(new DenialCursor('sha256:container-1', '2026-08-19T10:00:00.000000001Z')))
        seam.roundEnvironment().readDenials().denials()*.finding()

        then: 'the round box reads its guard log from the committed position, not from the start'
        docker.runs.last() == GuardCommands.guardLogs(KEY, 1000, '2026-08-19T10:00:00.000000001Z')
    }

    // FR5 of fix-denial-report-attachment: the offer names the ROUND box's guard container, so
    // only the round box can ever match it. A judge or verification box consuming it would reject
    // it on every read — a foreign-source INFO line and a synthetic "denials may be lost" marker
    // in that box's own findings, both false: its guard simply is a different container.
    def "FR5: a restored cursor is offered to the round box alone, not to the #role box"() {
        given: 'the guard container named by the committed cursor is the round box\'s, not this role\'s'
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardId(KEY + suffix) ? new DockerResult(0, 'sha256:role-box\n', '')
            : new DockerResult(0, '', '')
        }
        def seam = environments(KEY)

        when:
        seam.restoreDenials(new DenialRestoration(
                        Optional.of(new DenialCursor('sha256:round-box', '2026-08-19T10:00:00Z')),
                        [
                            new DenialIdentity('sha256:round-box', '2026-08-19T09:00:00Z')
                        ] as Set))
        def read = freshBox(seam, role).readDenials()

        then: 'no loss marker is minted, and the log is read from the start like any fresh box'
        read.denials() == []
        docker.runs.last() == GuardCommands.guardLogs(KEY + suffix, 1000, null)

        where:
        role | suffix
        'judge' | '-j'
        'verification' | '-v'
    }

    private static SelfCheckedEnvironment freshBox(ContainerEnvironments seam, String role) {
        role == 'judge' ? seam.judgeEnvironment() : seam.verificationEnvironment()
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
