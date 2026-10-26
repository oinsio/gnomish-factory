package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.PreconditionsFixture.IMAGE

import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import spock.lang.Specification

/**
 * FR1, FR4, NFR-R1 of add-doctor-command (design D1; NG1): the sandbox image line is the startup
 * owner's contract verdict through the inspection alone — never the assessment, whose floor probe
 * starts a container — and every line names the box git floor as not probed.
 */
class SandboxImageDoctorCheckSpec extends Specification {

    TaskBranchGit branches = Stub()
    SandboxImageReadiness readiness = Mock()

    def check() {
        new SandboxImageDoctorCheck(PreconditionsFixture.preconditions(branches, readiness), PreconditionsFixture.sandbox())
    }

    def "FR1, NG1: an image with the factory's contract passes by the inspection alone, the floor named as not probed"() {
        when:
        def verdicts = check().run(DoctorSpecSupport.bound(true, false))

        then:
        1 * readiness.inspectContract(IMAGE)
        0 * readiness.assess(_)
        0 * readiness._
        verdicts == [
            new DoctorVerdict(DoctorCheckId.SANDBOX_IMAGE, DoctorStatus.OK,
            IMAGE + " is present locally with the factory's image contract" + SandboxImageDoctorCheck.FLOOR_NOT_PROBED, null)
        ]
    }

    def "FR4, NG1: #label fails with the inspection's own message, the floor named as not probed"() {
        given:
        readiness.inspectContract(IMAGE) >> { throw failure }

        when:
        def verdicts = check().run(DoctorSpecSupport.bound(true, false))

        then:
        verdicts == [
            new DoctorVerdict(DoctorCheckId.SANDBOX_IMAGE, DoctorStatus.FAIL, detail + SandboxImageDoctorCheck.FLOOR_NOT_PROBED, null)
        ]

        where:
        label | failure || detail
        'another contract' | refusal() || refusal().message
        'an image the runtime does not hold' | new IllegalStateException('No such image') || 'the check could not judge: No such image'
    }

    def "D3: no container-bound stage skips the check without inspecting"() {
        when:
        def verdicts = check().run(DoctorSpecSupport.bound(false, true))

        then:
        0 * readiness._
        check().id() == DoctorCheckId.SANDBOX_IMAGE
        verdicts*.status() == [DoctorStatus.SKIPPED]
    }

    private static ImageContractRefusedException refusal() {
        new ImageContractRefusedException(IMAGE, null, 'io.gnomish.image-contract', 2, 'rebuild')
    }
}
