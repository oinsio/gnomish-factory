package com.github.oinsio.gnomish.app.doctor

import spock.lang.Specification

/**
 * FR1, FR2 of add-doctor-command: the pipeline line reports the run's one reading of the clone's
 * law — ok when it loaded, one failed line per located error when it did not, skipped outside a
 * project.
 */
class PipelineDoctorCheckSpec extends Specification {

    def check = new PipelineDoctorCheck()

    def "FR1: a law that loads and validates passes, counting its stages"() {
        expect:
        check.id() == DoctorCheckId.PIPELINE
        check.run(DoctorSpecSupport.bound(false, true, DoctorSpecSupport.definition(false, ['plan', 'build']))) == [
            new DoctorVerdict(DoctorCheckId.PIPELINE, DoctorStatus.OK, 'loads and validates: 2 stages', null)
        ]
    }

    def "FR1, UX1: every located error of a law that did not load is its own failed line, with the reading's fix"() {
        expect:
        check.run(DoctorSpecSupport.unloaded([
            'config.yaml:3: bad',
            'stages/x.yaml:1: worse'
        ])) == [
            new DoctorVerdict(DoctorCheckId.PIPELINE, DoctorStatus.FAIL, 'config.yaml:3: bad', DoctorSpecSupport.LAW_FIX),
            new DoctorVerdict(DoctorCheckId.PIPELINE, DoctorStatus.FAIL, 'stages/x.yaml:1: worse', DoctorSpecSupport.LAW_FIX),
        ]
    }

    def "FR2: outside a project the check is skipped"() {
        expect:
        check.run(DoctorSpecSupport.withoutProject())*.status() == [DoctorStatus.SKIPPED]
    }
}
