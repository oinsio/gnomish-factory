package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.CLONE

import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR1, FR2, FR4 of add-doctor-command (design D1): the clone's ref backend is the verdict of the
 * startup owner's ref-format check, its refusal reported with the check's own message; skipped
 * outside a project, and judged even when the pipeline did not load.
 */
class RefFormatDoctorCheckSpec extends Specification {

    TaskBranchGit branches = Mock()
    SandboxImageReadiness readiness = Mock()

    def check() {
        new RefFormatDoctorCheck(PreconditionsFixture.preconditions(branches, readiness))
    }

    def "FR1: a clone on the required backend passes, and nothing but the ref format is asked"() {
        when:
        def verdicts = check().run(context)

        then:
        1 * branches.requireRefFormat(CLONE.clonePath())
        0 * _
        verdicts == [
            new DoctorVerdict(DoctorCheckId.REF_FORMAT, DoctorStatus.OK,
            "${CLONE.clonePath()} passes the startup ref-format check".toString(), null)
        ]

        where:
        context << [
            DoctorSpecSupport.bound(true, false),
            DoctorSpecSupport.unloaded()
        ]
    }

    def "FR4: a clone on another backend fails with the startup refusal's own message"() {
        given:
        def refusal = new CloneRefFormatRefusedException('/src/widgets', 'reftable', UntrustedText.subprocess('files'))
        branches.requireRefFormat(_) >> { throw refusal }

        expect:
        check().run(DoctorSpecSupport.bound(true, false)) == [
            new DoctorVerdict(DoctorCheckId.REF_FORMAT, DoctorStatus.FAIL, refusal.message, null)
        ]
    }

    def "FR4: a failure the check cannot classify fails with what it threw"() {
        given:
        branches.requireRefFormat(_) >> {
            throw new IllegalStateException('git died')
        }

        expect:
        check().run(DoctorSpecSupport.bound(true, false)) == [
            new DoctorVerdict(DoctorCheckId.REF_FORMAT, DoctorStatus.FAIL, 'the check could not judge: git died', null)
        ]
    }

    def "FR2: outside a project the check is skipped and git is not asked"() {
        when:
        def verdicts = check().run(DoctorSpecSupport.withoutProject())

        then:
        0 * _
        check().id() == DoctorCheckId.REF_FORMAT
        verdicts*.status() == [DoctorStatus.SKIPPED]
    }
}
