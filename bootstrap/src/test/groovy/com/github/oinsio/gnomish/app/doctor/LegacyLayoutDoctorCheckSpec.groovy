package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.app.project.FactoryHome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR4 of add-doctor-command (design D4): leftovers of the pre-registry layout are the factory
 * home's own answer, a warning each with where the state lives now; a host-level check, run with or
 * without a project.
 */
class LegacyLayoutDoctorCheckSpec extends Specification {

    @TempDir
    Path root

    def "FR1: a clean home is one ok line"() {
        given:
        def check = new LegacyLayoutDoctorCheck(FactoryHome.at(root))

        expect:
        check.id() == DoctorCheckId.LEGACY_LAYOUT
        check.run(DoctorSpecSupport.withoutProject()) == [
            new DoctorVerdict(DoctorCheckId.LEGACY_LAYOUT, DoctorStatus.OK, "no leftover in ${root}".toString(), null)
        ]
    }

    def "FR1: each leftover warns with its successor, and the fix moves it there"() {
        given:
        Files.createDirectories(root.resolve('serve'))
        Files.createDirectories(root.resolve('worktrees'))
        def home = FactoryHome.at(root)

        expect:
        new LegacyLayoutDoctorCheck(home).run(DoctorSpecSupport.bound(true, false)) == home.legacyFolders().collect {
            new DoctorVerdict(DoctorCheckId.LEGACY_LAYOUT, DoctorStatus.WARN,
            "${it.path()} is a leftover of the pre-registry layout; its state now lives in ${it.successor()}".toString(),
            "move what you still need to ${it.successor()}, then remove ${it.path()}".toString())
        }
        home.legacyFolders().size() == 2
    }

    def "FR1, UX1: a home the owner cannot list warns that it could not be inspected, naming the folder to make listable"() {
        given: 'a secrets folder no one may list'
        def secrets = Files.createDirectories(root.resolve('secrets'))
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString('---------'))

        when:
        def verdicts = new LegacyLayoutDoctorCheck(FactoryHome.at(root)).run(DoctorSpecSupport.withoutProject())

        then:
        verdicts*.status() == [DoctorStatus.WARN]
        verdicts[0].detail().startsWith('the factory home could not be inspected: cannot list ')
        verdicts[0].fix() == "make ${root} and its folders listable by the factory's user (ls -la ${root}), then run gnomish doctor again".toString()

        cleanup:
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString('rwx------'))
    }
}
