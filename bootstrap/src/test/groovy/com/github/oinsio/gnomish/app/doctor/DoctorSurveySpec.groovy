package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.CLONE
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.definition
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.readings

import com.github.oinsio.gnomish.app.DoctorProjectReadings.StageBindings
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.sandbox.BindingProperties
import java.nio.file.Path
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import spock.lang.Specification

/**
 * FR1, FR2, FR4, FR7 of add-doctor-command (design D3): the survey reads the directory's published
 * resolution and, on a registered clone, its pipeline law once through the owners — a law that does
 * not load, or whose bindings do not resolve, is a reading the pipeline check reports, never a
 * refusal.
 */
class DoctorSurveySpec extends Specification {

    PipelineSource source = Mock()

    def "FR1: a law that loads is read once, with the adapters its stages bind"() {
        given:
        def survey = new DoctorSurvey(published(new ProjectResolution.Registered(CLONE)),
                readings([:], [source: source, bindings: new BindingProperties('container', [build: 'host'])]))

        when:
        def context = survey.survey()

        then:
        1 * source.load(CLONE.clonePath()) >> new LoadOutcome.Loaded(definition(false, ['plan', 'build']))
        0 * source._
        context.onPipeline(DoctorCheckId.PIPELINE, { [it] as List }) == [
            new PipelineReading.Loaded(definition(false, ['plan', 'build']), new StageBindings(true, true))
        ]
    }

    def "FR1, FR7: #label is an unloaded reading carrying its reasons"() {
        given:
        source.load(_) >> outcome
        def survey = new DoctorSurvey(published(new ProjectResolution.Registered(CLONE)),
                readings([:], [source: source, bindings: bindings]))

        expect:
        survey.read(CLONE) == new PipelineReading.Unloaded(reasons, fix)

        where:
        label | outcome | bindings || reasons | fix
        'a law with located errors' | invalid() | new BindingProperties('host', [:]) || [
            new ConfigError('config.yaml', 'line 3', 'bad').render().forConsole()
        ] | DoctorSurvey.LAW_FIX
        'a binding no adapter serves' | new LoadOutcome.Loaded(definition()) | new BindingProperties('nowhere', [:]) || [
            'the stage bindings do not resolve: ' + bindingRefusal()
        ] | DoctorSurvey.BINDINGS_FIX
    }

    def "FR1, NFR-O1, UX1: a law that cannot be read at all is an unloaded reading naming the fault (#label)"() {
        given:
        source.load(_) >> { throw new IOException((String) message) }
        def survey = new DoctorSurvey(published(new ProjectResolution.Registered(CLONE)), readings([:], [source: source]))

        expect:
        survey.read(CLONE) == new PipelineReading.Unloaded([
            'the pipeline law could not be read: ' + detail
        ], "make the pipeline law of ${CLONE.clonePath()} readable, then run gnomish doctor again".toString())

        where:
        label | message || detail
        'its words' | 'disk gone' || 'java.io.IOException: disk gone'
        'no words' | null || 'java.io.IOException'
        'hostile words' | 'gone \u001b[2J' || 'java.io.IOException: gone ^[[2J'
    }

    def "FR2: outside a registered clone nothing is read"() {
        given:
        def unregistered = new ProjectResolution.Unregistered(Path.of('/tmp/x'), false, 'r')
        def survey = new DoctorSurvey(published(unregistered), readings([:], [source: source]))

        when:
        def context = survey.survey()

        then:
        0 * source._
        context.project() == unregistered
    }

    def "FR2: a run that published no resolution has none"() {
        expect:
        new DoctorSurvey(new DefaultListableBeanFactory().getBeanProvider(ProjectResolution), readings()).survey().project() == null
    }

    private static LoadOutcome invalid() {
        new LoadOutcome.Invalid([
            new ConfigError('config.yaml', 'line 3', 'bad')
        ])
    }

    private static String bindingRefusal() {
        try {
            new com.github.oinsio.gnomish.sandbox.BindingResolver(new BindingProperties('nowhere', [:]),
            com.github.oinsio.gnomish.adapter.sandbox.DiscoveredBindings.real())
            throw new AssertionError('expected a refusal')
        } catch (IllegalArgumentException e) {
            e.message
        }
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> published(T value) {
        def beans = new DefaultListableBeanFactory()
        beans.registerSingleton('projectResolution', value)
        beans.getBeanProvider(value.getClass() as Class<T>)
    }
}
