package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import spock.lang.Specification

/**
 * FR1, FR4 of add-doctor-command (design D1, D3): Docker reachable is the startup plan's verdict on
 * the container runtime — its own sentence when the runtime is down, its configuration refusal when
 * the plan refuses first — and runs only when a stage binds the container adapter.
 */
class DockerDoctorCheckSpec extends Specification {

    TaskBranchGit branches = Stub()
    SandboxImageReadiness readiness = Stub()

    def "FR1: a runtime that answers passes"() {
        given:
        def check = new DockerDoctorCheck(PreconditionsFixture.preconditions(branches, readiness, true))

        expect:
        check.id() == DoctorCheckId.DOCKER
        check.run(DoctorSpecSupport.bound(true, false)) == [
            new DoctorVerdict(DoctorCheckId.DOCKER, DoctorStatus.OK, 'the Docker runtime answers', null)
        ]
    }

    def "FR4: an unreachable runtime fails with the startup plan's own sentence"() {
        when:
        def verdicts = new DockerDoctorCheck(PreconditionsFixture.preconditions(branches, readiness, false))
                .run(DoctorSpecSupport.bound(true, false))

        then:
        verdicts.size() == 1
        verdicts[0].status() == DoctorStatus.FAIL
        verdicts[0].detail().contains('the Docker runtime is unreachable')
    }

    def "FR4: a plan the configuration refuses fails with the plan's refusal"() {
        when: 'a container-bound plan with no sandbox image configured'
        def verdicts = new DockerDoctorCheck(PreconditionsFixture.preconditions(branches, readiness, true, 'container', null))
                .run(DoctorSpecSupport.bound(true, false))

        then:
        verdicts*.status() == [DoctorStatus.FAIL]
        verdicts[0].detail().startsWith('the check could not judge: ')
        verdicts[0].detail().contains('factory.sandbox.image is not set')
    }

    def "D3: no container-bound stage skips the check"() {
        expect:
        new DockerDoctorCheck(PreconditionsFixture.preconditions(branches, readiness, false))
                .run(DoctorSpecSupport.bound(false, true)) == [
                    new DoctorVerdict(DoctorCheckId.DOCKER, DoctorStatus.SKIPPED, 'no stage binds the container adapter', null)
                ]
    }
}
