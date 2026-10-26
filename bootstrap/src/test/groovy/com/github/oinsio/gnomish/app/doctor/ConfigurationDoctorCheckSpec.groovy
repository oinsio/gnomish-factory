package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.app.ConfigurationViolations
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import spock.lang.Specification

/**
 * FR1, FR4, FR7 of add-doctor-command (design D1): the configuration check lists the violations the
 * loader published for doctor, one failed line each, and reads no file.
 */
class ConfigurationDoctorCheckSpec extends Specification {

    def "FR7: every collected violation is its own failed line, in the order found"() {
        expect:
        check(new ConfigurationViolations([
            'a: unknown key x',
            'b: FACTORY_Y is set'
        ])).run(DoctorSpecSupport.withoutProject()) == [
            new DoctorVerdict(DoctorCheckId.CONFIGURATION, DoctorStatus.FAIL, 'a: unknown key x', null),
            new DoctorVerdict(DoctorCheckId.CONFIGURATION, DoctorStatus.FAIL, 'b: FACTORY_Y is set', null),
        ]
    }

    def "FR1: a clean configuration is one ok line"() {
        expect:
        check(ConfigurationViolations.NONE).run(DoctorSpecSupport.withoutProject()) == [
            new DoctorVerdict(DoctorCheckId.CONFIGURATION, DoctorStatus.OK, 'no violation', null)
        ]
    }

    def "FR1: a run that collected nothing says so, never passing"() {
        given:
        def check = new ConfigurationDoctorCheck(new DefaultListableBeanFactory().getBeanProvider(ConfigurationViolations))

        expect:
        check.id() == DoctorCheckId.CONFIGURATION
        check.run(DoctorSpecSupport.withoutProject()) == [
            new DoctorVerdict(DoctorCheckId.CONFIGURATION, DoctorStatus.SKIPPED, 'the configuration was not collected in this run', null)
        ]
    }

    private static ConfigurationDoctorCheck check(ConfigurationViolations violations) {
        def beans = new DefaultListableBeanFactory()
        beans.registerSingleton('configurationViolations', violations)
        new ConfigurationDoctorCheck(beans.getBeanProvider(ConfigurationViolations))
    }
}
