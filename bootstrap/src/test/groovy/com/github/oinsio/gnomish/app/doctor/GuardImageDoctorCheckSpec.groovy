package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import spock.lang.Specification

/**
 * FR1, NFR-R1 of add-doctor-command: the guard image line is one presence inspection of the
 * configured guard image; an absent image only warns, since a box pulls it on first use.
 */
class GuardImageDoctorCheckSpec extends Specification {

    static final String GUARD = 'mirror.example/mitmproxy:12'

    SandboxImageReadiness readiness = Mock()
    def check = new GuardImageDoctorCheck(readiness,
    new com.github.oinsio.gnomish.sandbox.SandboxProperties(null, null, GUARD, null, [], [], false, null, null, null, null))

    def "FR1: a guard image held locally passes, by one presence inspection"() {
        when:
        def verdicts = check.run(DoctorSpecSupport.bound(true, false))

        then:
        1 * readiness.inspectPresence(GUARD)
        0 * readiness._
        verdicts == [
            new DoctorVerdict(DoctorCheckId.GUARD_IMAGE, DoctorStatus.OK, GUARD + ' is present locally', null)
        ]
    }

    // Scenario: cli/operator-doctor / Absent guard image and old layout leftovers only warn
    def "FR1, NFR-O1: an absent guard image warns (#label), with the pull that fetches it"() {
        given:
        readiness.inspectPresence(GUARD) >> {
            throw new IllegalStateException((String) message)
        }

        expect:
        check.run(DoctorSpecSupport.bound(true, false)) == [
            new DoctorVerdict(DoctorCheckId.GUARD_IMAGE, DoctorStatus.WARN,
            GUARD + " is not present locally (${words}); a box pulls it on first use".toString(), 'docker pull ' + GUARD)
        ]

        where:
        label | message || words
        "the runtime's words" | 'No such image' || 'No such image'
        'no words' | null || 'java.lang.IllegalStateException'
        'a control sequence in the answer' | 'No such \u001b[2Jimage' || 'No such ^[[2Jimage'
    }

    def "D3: no container-bound stage skips the check without inspecting"() {
        when:
        def verdicts = check.run(DoctorSpecSupport.bound(false, true))

        then:
        0 * readiness._
        check.id() == DoctorCheckId.GUARD_IMAGE
        verdicts*.status() == [DoctorStatus.SKIPPED]
    }
}
