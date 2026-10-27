package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.TRACKER_TYPE
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.bound
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.definition
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.readings
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.unloaded
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.withoutProject

import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.tracker.TrackerReadVerdict
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR3, NG5 of add-doctor-command (design D7): {@link TrackerReadProbe} turns the adapter's own read
 * verdict, relayed by the tracker wiring, into one line — reading confirmed with what startup will
 * create and that write access is not verified; a refusal with the status, the adapter's cause and,
 * when the tracker reported them, its accepted permissions rendered for the console; skipped where
 * nothing can be probed. The adapter is never built ({@code create} is not called).
 */
class TrackerReadProbeSpec extends Specification {

    TrackerAdapterFactory factory = Mock()

    private List<DoctorVerdict> probe() {
        def probe = new TrackerReadProbe(readings([:], [registry: [(TRACKER_TYPE): factory]]))
        def verdicts = probe.run(bound(false, true, definition(true)))
        verdicts
    }

    def "FR3: a confirmed read names what startup will create and says write access is not verified"() {
        when:
        def verdicts = probe()

        then:
        1 * factory.probeRead(_, {
            it.type() == TRACKER_TYPE
        }) >> new TrackerReadVerdict.ReadConfirmed([
            'gnomish:ready',
            'gnomish:delivered'
        ])
        0 * factory.create(_)
        verdicts == [
            new DoctorVerdict(DoctorCheckId.TRACKER_PROBE, DoctorStatus.OK,
            'reading confirmed; startup will create gnomish:ready, gnomish:delivered; write access is not verified', null)
        ]
    }

    def "FR3: a confirmed read with nothing missing says startup will create nothing"() {
        when:
        def verdicts = probe()

        then:
        1 * factory.probeRead(_, _) >> new TrackerReadVerdict.ReadConfirmed([])
        verdicts*.detail == [
            'reading confirmed; startup will create nothing; write access is not verified'
        ]
    }

    def "FR3: a refusal is a failure with the status and the cause, and the accepted permissions rendered for the console"() {
        when:
        def verdicts = probe()

        then:
        1 * factory.probeRead(_, _) >> new TrackerReadVerdict.Refused(403, 'token lacks write access to this repo',
                Optional.of(UntrustedText.tracker('issues=write\u001b[31m')))
        verdicts == [
            new DoctorVerdict(DoctorCheckId.TRACKER_PROBE, DoctorStatus.FAIL,
            'the tracker refused the probe with status 403: token lacks write access to this repo; ' +
            'the tracker accepts the permissions: ' + UntrustedText.tracker('issues=write\u001b[31m').forConsole(),
            TrackerReadProbe.REFUSED_FIX)
        ]
        !verdicts[0].detail.contains('\u001b')
    }

    def "FR3: a refusal without reported permissions carries the status and the cause only"() {
        when:
        def verdicts = probe()

        then:
        1 * factory.probeRead(_, _) >> new TrackerReadVerdict.Refused(404, 'repo not found', Optional.empty())
        verdicts*.detail == [
            'the tracker refused the probe with status 404: repo not found'
        ]
        verdicts*.status == [DoctorStatus.FAIL]
    }

    def "FR3, UX1: an unreadable answer is a failure carrying the adapter's cause and its fix"() {
        when:
        def verdicts = probe()

        then:
        1 * factory.probeRead(_, _) >> new TrackerReadVerdict.Malformed('the label listing is not a JSON array')
        verdicts == [
            new DoctorVerdict(DoctorCheckId.TRACKER_PROBE, DoctorStatus.FAIL,
            'the tracker answered the probe, but the label listing is not a JSON array', TrackerReadProbe.MALFORMED_FIX)
        ]
    }

    def "FR3: an adapter with nothing to probe is skipped, naming the type"() {
        when:
        def verdicts = probe()

        then:
        1 * factory.probeRead(_, _) >> new TrackerReadVerdict.NotApplicable()
        verdicts == [
            new DoctorVerdict(DoctorCheckId.TRACKER_PROBE, DoctorStatus.SKIPPED,
            "tracker type '${TRACKER_TYPE}' has no remote binding to probe; no call was made".toString(), null)
        ]
    }

    def "FR3: a law without a tracker is skipped and the adapter is not asked"() {
        when:
        def verdicts = new TrackerReadProbe(readings([:], [registry: [(TRACKER_TYPE): factory]]))
        .run(bound(false, true, definition(false)))

        then:
        0 * factory._
        verdicts == [
            new DoctorVerdict(DoctorCheckId.TRACKER_PROBE, DoctorStatus.SKIPPED, 'the pipeline law declares no tracker', null)
        ]
    }

    def "FR3, UX1: an unregistered tracker type is a failure with the wiring's unknown-type line and its fix"() {
        when:
        def verdicts = new TrackerReadProbe(readings([:], [registry: [other: factory]]))
        .run(bound(false, true, definition(true)))

        then:
        0 * factory._
        verdicts*.status == [DoctorStatus.FAIL]
        verdicts[0].detail == "unknown tracker type '${TRACKER_TYPE}' — supported: other".toString()
        verdicts[0].fix == DoctorText.TRACKER_TYPE_FIX
    }

    def "NFR-O1: an unregistered tracker type the law spells with a control sequence is rendered for the console"() {
        given:
        def law = definition(true)
        def hostile = new com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition(law.schemaVersion(),
                law.defaultLimits(), law.stages(), new com.github.oinsio.gnomish.domain.pipeline.TrackerConfig('x\u001b[2J', 3))

        when:
        def verdicts = new TrackerReadProbe(readings([:], [registry: [other: factory]])).run(bound(false, true, hostile))

        then:
        verdicts*.detail == [
            "unknown tracker type 'x^[[2J' — supported: other"
        ]
    }

    def "FR2: without a project, or with a law that did not load, the probe is skipped and nothing is asked"() {
        when:
        def verdicts = new TrackerReadProbe(readings([:], [registry: [(TRACKER_TYPE): factory]])).run(context)

        then:
        0 * factory._
        verdicts*.status == [DoctorStatus.SKIPPED]
        verdicts*.check == [DoctorCheckId.TRACKER_PROBE]

        where:
        context << [withoutProject(), unloaded()]
    }
}
