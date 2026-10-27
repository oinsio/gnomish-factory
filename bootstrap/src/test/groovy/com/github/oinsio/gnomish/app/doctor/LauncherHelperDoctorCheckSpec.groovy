package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.subprocess.HelperVerdict
import spock.lang.Specification

/**
 * FR1, FR4 of add-doctor-command (design D4): when a stage is host-bound, the process-group
 * launcher and the group sweep's signaller are the launch owner's own verdict; a missing one fails
 * naming the tool and, as the fix, the package the owner names.
 */
class LauncherHelperDoctorCheckSpec extends Specification {

    HostLaunchAssessment launch = Stub()

    def "FR1: present helpers pass, naming both"() {
        given:
        launch.helper() >> new HelperVerdict.Present('/usr/bin/setsid', '/bin/kill')
        def check = new LauncherHelperDoctorCheck(launch)

        expect:
        check.id() == DoctorCheckId.LAUNCHER_HELPER
        check.run(DoctorSpecSupport.bound(false, true)) == [
            new DoctorVerdict(DoctorCheckId.LAUNCHER_HELPER, DoctorStatus.OK, '/usr/bin/setsid and /bin/kill are present', null)
        ]
    }

    // Scenario: cli/operator-doctor / Missing launcher helper
    // Scenario: cli/operator-doctor / Missing group sweep signaller
    def "FR1: a missing #tool fails with the owner's reason, the fix naming its package"() {
        given:
        launch.helper() >> new HelperVerdict.Missing(tool, reason, install)

        expect:
        new LauncherHelperDoctorCheck(launch).run(DoctorSpecSupport.bound(false, true)) == [
            new DoctorVerdict(DoctorCheckId.LAUNCHER_HELPER, DoctorStatus.FAIL, reason, install)
        ]

        where:
        tool | reason | install
        'setsid' | 'setsid is not on PATH' | 'install util-linux'
        '/bin/kill' | '/bin/kill is absent' | 'install procps or util-linux'
    }

    def "D3: no host-bound stage skips the check"() {
        expect:
        new LauncherHelperDoctorCheck(launch).run(DoctorSpecSupport.bound(true, false))*.status() == [DoctorStatus.SKIPPED]
    }
}
