package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.TRACKER_TOKEN
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.readings

import com.github.oinsio.gnomish.app.SecretLocation
import com.github.oinsio.gnomish.app.SecretLocator
import com.github.oinsio.gnomish.app.UsageException
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * FR1, FR4, NFR-S1 of add-doctor-command: one line per secret the tracker and the checks declare,
 * each located by the secrets adapter's own search — the place it was found, or every place
 * searched — and never its value.
 */
class SecretsDoctorCheckSpec extends Specification {

    SecretLocator locator = Mock()

    def "FR1, NFR-S1: a declared secret that resolves is ok and names the place it was found in"() {
        given:
        def check = new SecretsDoctorCheck(readings(), locator)

        when:
        def verdicts = check.run(DoctorSpecSupport.bound(false, true, DoctorSpecSupport.definition(true)))

        then:
        1 * locator.locate(TRACKER_TOKEN) >> new SecretLocation.Found('/home/secrets/' + TRACKER_TOKEN)
        check.id() == DoctorCheckId.SECRETS
        verdicts == [
            new DoctorVerdict(DoctorCheckId.SECRETS, DoctorStatus.OK, "${TRACKER_TOKEN} found in /home/secrets/${TRACKER_TOKEN}".toString(), null)
        ]
    }

    def "FR1: a declared secret that resolves nowhere fails, naming every place searched"() {
        given:
        locator.locate(TRACKER_TOKEN) >> new SecretLocation.Absent([
            '/p/secrets/T',
            'the environment variable T'
        ])

        expect:
        new SecretsDoctorCheck(readings(), locator).run(DoctorSpecSupport.bound(false, true, DoctorSpecSupport.definition(true))) == [
            new DoctorVerdict(DoctorCheckId.SECRETS, DoctorStatus.FAIL,
            "${TRACKER_TOKEN} not found; searched /p/secrets/T, the environment variable T".toString(),
            "provide ${TRACKER_TOKEN} in one of the places searched".toString())
        ]
    }

    def "FR1, NFR-O1, UX1: a place the adapter refuses fails with the adapter's own refusal (#label), its fix pointing at it"() {
        given:
        locator.locate(TRACKER_TOKEN) >> {
            throw new UsageException(message)
        }

        expect:
        new SecretsDoctorCheck(readings(), locator).run(DoctorSpecSupport.bound(false, true, DoctorSpecSupport.definition(true))) == [
            new DoctorVerdict(DoctorCheckId.SECRETS, DoctorStatus.FAIL, detail, DoctorText.AS_THE_LINE_NAMES)
        ]

        where:
        label | message || detail
        'its words' | 'secret file x is readable by others: run chmod 600 x' || 'secret file x is readable by others: run chmod 600 x'
        'no words' | null || UsageException.name
        'hostile words' | 'secret file \u001b[2Jx is readable' || 'secret file ^[[2Jx is readable'
    }

    def "FR1, UX1: a tracker type no adapter serves fails with the tracker wiring's refusal and its fix"() {
        given:
        def definition = DoctorSpecSupport.definition(true)
        def unknown = new com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition(definition.schemaVersion(),
                definition.defaultLimits(), definition.stages(), new TrackerConfig('nowhere', 3))

        when:
        def verdicts = new SecretsDoctorCheck(readings(), locator).run(DoctorSpecSupport.bound(false, true, unknown))

        then:
        0 * locator._
        verdicts*.status() == [DoctorStatus.FAIL]
        verdicts[0].detail().startsWith("unknown tracker type 'nowhere'")
        verdicts[0].fix() == DoctorText.TRACKER_TYPE_FIX
    }

    def "FR1: a pipeline that declares no secret is one ok line"() {
        when:
        def verdicts = new SecretsDoctorCheck(readings(), locator).run(DoctorSpecSupport.bound(false, true))

        then:
        0 * locator._
        verdicts == [
            new DoctorVerdict(DoctorCheckId.SECRETS, DoctorStatus.OK, 'no secret is declared', null)
        ]
    }

    def "FR2: without a loaded law the check is skipped"() {
        expect:
        new SecretsDoctorCheck(readings(), locator).run(context)*.status() == [DoctorStatus.SKIPPED]

        where:
        context << [
            DoctorSpecSupport.withoutProject(),
            DoctorSpecSupport.unloaded()
        ]
    }
}
