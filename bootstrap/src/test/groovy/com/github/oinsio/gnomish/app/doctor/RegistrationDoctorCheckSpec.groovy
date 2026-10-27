package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.app.project.ProjectResolution
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR2 of add-doctor-command: the registration line is the registry's own answer for the
 * directory — a registered clone passes; a directory {@code --dir} named fails with the registry's
 * {@code project add} line; the working directory outside any clone only skips, with the same line.
 */
class RegistrationDoctorCheckSpec extends Specification {

    static final Path DIR = Path.of('/src/elsewhere')
    static final String REFUSAL = '/src/elsewhere is not a registered clone; register it with: gnomish project add elsewhere --dir=/src/elsewhere'

    def check = new RegistrationDoctorCheck()

    def "FR1: a registered clone names its clone and project"() {
        expect:
        check.id() == DoctorCheckId.REGISTRATION
        check.run(DoctorSpecSupport.bound(false, true)) == [
            new DoctorVerdict(DoctorCheckId.REGISTRATION, DoctorStatus.OK,
            "${DoctorSpecSupport.CLONE.clonePath()} is clone main of project widgets".toString(), null)
        ]
    }

    def "FR1, FR2, UX1: an unregistered directory is #status when --dir named it is #named, with the project add line"() {
        given:
        def context = DoctorContext.of(new ProjectResolution.Unregistered(DIR, named, REFUSAL), {
            throw new AssertionError()
        })

        expect:
        check.run(context) == [
            new DoctorVerdict(DoctorCheckId.REGISTRATION, status, REFUSAL, fix)
        ]

        where:
        named || status | fix
        true || DoctorStatus.FAIL | DoctorText.AS_THE_LINE_NAMES
        false || DoctorStatus.SKIPPED | null
    }

    def "NFR-O1: a refusal quoting a hostile directory name is rendered for the console"() {
        given:
        def context = DoctorContext.of(new ProjectResolution.Unregistered(DIR, true, '/src/\u001b[2Jx is not a registered clone'), {
            throw new AssertionError()
        })

        expect:
        check.run(context)*.detail() == [
            '/src/^[[2Jx is not a registered clone'
        ]
    }

    def "FR1, FR2: an unread registry is #status when --dir named it is #named, pointing at the configuration"() {
        given:
        def context = DoctorContext.of(new ProjectResolution.Unread(DIR, named), {
            throw new AssertionError()
        })

        expect:
        check.run(context) == [
            new DoctorVerdict(DoctorCheckId.REGISTRATION, status,
            'the project registry could not be read, so /src/elsewhere could not be judged',
            'correct the project file the operator configuration line names')
        ]

        where:
        named || status
        true || DoctorStatus.FAIL
        false || DoctorStatus.SKIPPED
    }

    def "FR2: a run that published no resolution skips the line"() {
        expect:
        check.run(DoctorContext.of(null, { throw new AssertionError() })) == [
            new DoctorVerdict(DoctorCheckId.REGISTRATION, DoctorStatus.SKIPPED, 'no directory was resolved in this run', null)
        ]
    }
}
