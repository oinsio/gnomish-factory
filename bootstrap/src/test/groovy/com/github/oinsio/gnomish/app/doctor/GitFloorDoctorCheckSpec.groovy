package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitVersionCheck
import com.github.oinsio.gnomish.gittransfer.GitVersion
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR4, FR7 of add-doctor-command (design D1): the git floor line is the verdict of {@code
 * GitVersionCheck.assess()} over the real runner, the floor and its reason {@link GitVersion}'s.
 */
class GitFloorDoctorCheckSpec extends Specification {

    @TempDir
    Path dir

    def "FR1, FR7: git #preset is reported #status"() {
        given:
        def check = new GitFloorDoctorCheck(new GitVersionCheck(
                        new GitProcessRunner(StandIn.link(dir.resolve('git'), preset).toString())))

        when:
        def verdicts = check.run(DoctorSpecSupport.withoutProject())

        then:
        check.id() == DoctorCheckId.GIT_FLOOR
        verdicts.size() == 1
        with(verdicts[0]) {
            it.check() == DoctorCheckId.GIT_FLOOR
            it.status() == status
            it.detail().startsWith(detail)
            it.fix() == fix
        }

        where:
        preset | status | detail | fix
        'version-2-56-0' | DoctorStatus.OK | "git 2.56.0 meets the floor ${GitVersion.FLOOR}" | null
        'version-2-55' | DoctorStatus.FAIL | "git 2.55.2 is below the floor ${GitVersion.FLOOR}: ${GitVersion.FLOOR_REASON}" | "install git ${GitVersion.FLOOR} or later on the factory's PATH"
        'version-broken' | DoctorStatus.FAIL | 'git reported no version (it printed: ' | "install git ${GitVersion.FLOOR} or later on the factory's PATH"
    }
}
