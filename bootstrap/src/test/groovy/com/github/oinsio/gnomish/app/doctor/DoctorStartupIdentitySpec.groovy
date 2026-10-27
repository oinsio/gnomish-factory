package com.github.oinsio.gnomish.app.doctor

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * M1 of add-doctor-command (G2, FR1, FR4; design D1): for each of the fourteen checks of FR1, one
 * data table runs every fixture of the check through the startup path — the owner {@code take},
 * {@code run} or the daemon's startup calls, in its refusing form — and through the real doctor
 * check over the same fixture, and asserts the same verdict: startup stops on the fixture exactly
 * when doctor reports the check as {@code fail}, and both answers carry the same reason. Where no
 * startup path refuses on a check (the version, the agent credential, the guard image, the
 * pre-registry layout), the row says so and asserts the identity against the value the startup
 * path reads instead. The hooks-path check of FR8, which no startup path decides, is pinned by its
 * owner's spec; the {@code --online} probe of FR3 by {@code GithubLabelProvisionerSpec}.
 */
class DoctorStartupIdentitySpec extends Specification {

    /** FR1's fourteen checks: every doctor check but FR8's hooks path and FR3's online probe. */
    static final Set<DoctorCheckId> FR1_CHECKS =
    (EnumSet.allOf(DoctorCheckId) - [
        DoctorCheckId.HOOKS_PATH,
        DoctorCheckId.TRACKER_PROBE
    ]) as Set

    static final List<IdentityRow> TABLE =
    HostIdentityRows.all() + new ProjectIdentityRows().all() + ContainerIdentityRows.all() + LaunchIdentityRows.all()

    /**
     * The checks no fixture can make the startup path refuse on, each with the reason its row
     * states.
     */
    static final Set<DoctorCheckId> NEVER_REFUSED = [
        DoctorCheckId.FACTORY_VERSION,
        // information, never a refusal
        DoctorCheckId.GUARD_IMAGE,
        // pulled on first use, never assessed at startup
        DoctorCheckId.LEGACY_LAYOUT,
        // read by no startup path
        DoctorCheckId.LAUNCHER_HELPER, // this host's own; the missing half is LaunchAssessmentSpec's
    ] as Set

    @TempDir
    Path dir

    // Scenario: cli/operator-doctor / Same verdict as startup
    def "M1, G2: #row — startup stops on a fixture exactly when doctor fails the check, for the same reason"() {
        when:
        List<IdentityRow.Case> cases = row.run(dir)

        then: 'every case is judged by both sides, and doctor answers under the row check alone'
        !cases.isEmpty()
        cases.every {
            !it.doctor.isEmpty() && it.doctor*.check().every {
                it == row.id
            }
        }

        and: 'startup refuses exactly when doctor fails'
        cases.each { judged ->
            assert judged.startupFails == judged.doctorFails(): "${row}: ${judged.label}"
        }

        and: 'the reason, or the value read, is the same on both sides'
        cases.each { judged ->
            judged.shared.each { String words ->
                assert judged.startupSays.contains(words): "${row}: ${judged.label}: startup says ${judged.startupSays}"
                assert judged.doctorSays().contains(words): "${row}: ${judged.label}: doctor says ${judged.doctorSays()}"
            }
        }

        and: 'a check startup can refuse on is judged on a fixture it refuses and on one it accepts'
        row.id in NEVER_REFUSED || (cases*.startupFails as Set) == [true, false] as Set

        where:
        row << TABLE
    }

    def "M1: the table has exactly one row per check of FR1 — fourteen"() {
        expect:
        TABLE.size() == 14
        FR1_CHECKS.size() == 14
        TABLE*.id as Set == FR1_CHECKS
    }
}
