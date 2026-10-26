package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.app.FactoryVersion
import spock.lang.Specification

/** FR1, FR4 of add-doctor-command: the factory version and the Java runtime, from their owners, informational. */
class FactoryVersionDoctorCheckSpec extends Specification {

    def "FR1: the version line is ok and names the factory version and the Java runtime, with or without a project"() {
        given:
        def check = new FactoryVersionDoctorCheck()

        expect:
        check.id() == DoctorCheckId.FACTORY_VERSION
        check.run(context) == [
            new DoctorVerdict(DoctorCheckId.FACTORY_VERSION, DoctorStatus.OK,
            "gnomish ${FactoryVersion.current().value()} on Java ${Runtime.version()}".toString(), null)
        ]

        where:
        context << [
            DoctorSpecSupport.withoutProject(),
            DoctorSpecSupport.bound(true, false)
        ]
    }
}
