package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.CLONE
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.bound
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.definition
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.unloaded

import com.github.oinsio.gnomish.app.DoctorProjectReadings.StageBindings
import com.github.oinsio.gnomish.app.project.ProjectResolution
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR2 of add-doctor-command (design D3): the context reads the pipeline once on a registered
 * clone and nowhere else, and is the one place a check is skipped — no project, a law that did not
 * load, no stage bound to the check's adapter — each with its reason.
 */
class DoctorContextSpec extends Specification {

    static final DoctorCheckId ID = DoctorCheckId.DOCKER
    static final List<DoctorVerdict> RAN = [
        new DoctorVerdict(ID, DoctorStatus.OK, 'ran', null)
    ]

    def "FR2: outside a registered clone every project check is skipped with the directory's answer: #label"() {
        given:
        def context = DoctorContext.of(project, {
            throw new AssertionError('no pipeline is read')
        })

        expect:
        context.project() == project
        [
            context.onClone(ID, { RAN }),
            context.onPipeline(ID, { RAN }),
            context.onDefinition(ID, { c, d -> RAN }),
            context.onContainer(ID, { c, d -> RAN }),
            context.onHost(ID, { c, d -> RAN }),
        ].every {
            it == [
                new DoctorVerdict(ID, DoctorStatus.SKIPPED, reason, null)
            ]
        }

        where:
        label | project || reason
        'unregistered' | new ProjectResolution.Unregistered(Path.of('/tmp/x'), false, 'r') || '/tmp/x is not a registered clone'
        'unread registry' | new ProjectResolution.Unread(Path.of('/tmp/y'), true) || 'the project registry could not be read for /tmp/y'
        'nothing published' | null || 'no directory was resolved'
    }

    def "FR1: on a registered clone the pipeline is read once and handed to the checks with the clone"() {
        given:
        def reads = 0
        def reading = new PipelineReading.Loaded(definition(), new StageBindings(true, true))
        def context = DoctorContext.of(new ProjectResolution.Registered(CLONE), { clone ->
            reads++
            assert clone == CLONE
            reading
        })

        expect:
        context.onClone(ID, { it == CLONE ? RAN : [] }) == RAN
        context.onPipeline(ID, { it.is(reading) ? RAN : [] }) == RAN
        context.onDefinition(ID, { c, d ->
            c == CLONE && d == reading.definition() ? RAN : []
        }) == RAN
        reads == 1
    }

    def "FR1, D3: a check bound to an adapter runs exactly when a stage binds it: container #container, host #host"() {
        given:
        def context = bound(container, host)

        expect:
        context.onContainer(ID, { c, d ->
            RAN
        }) == (container ? RAN : skipped('no stage binds the container adapter'))
        context.onHost(ID, { c, d ->
            RAN
        }) == (host ? RAN : skipped('no stage binds the host adapter'))
        context.onDefinition(ID, { c, d -> RAN }) == RAN

        where:
        container | host
        true | false
        false | true
        true | true
    }

    def "FR1: a law that did not load skips the checks that need it, but not the pipeline check's reading"() {
        given:
        def context = unloaded(['bad'])

        expect:
        context.onDefinition(ID, { c, d ->
            RAN
        }) == skipped('the pipeline law did not load')
        context.onContainer(ID, { c, d ->
            RAN
        }) == skipped('the pipeline law did not load')
        context.onPipeline(ID, {
            it == new PipelineReading.Unloaded(['bad'], DoctorSpecSupport.LAW_FIX) ? RAN : []
        }) == RAN
        context.onClone(ID, { RAN }) == RAN
    }

    private static List<DoctorVerdict> skipped(String reason) {
        [
            new DoctorVerdict(ID, DoctorStatus.SKIPPED, reason, null)
        ]
    }
}
