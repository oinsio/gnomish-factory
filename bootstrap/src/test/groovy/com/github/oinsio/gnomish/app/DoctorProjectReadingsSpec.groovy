package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.CLONE
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.TRACKER_TOKEN
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.definition
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.readings

import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import spock.lang.Specification

/**
 * FR1, FR4, NFR-S1 of add-doctor-command: what doctor reads about a clone comes from the owners the
 * run path asks — the pipeline source's full load, the binding resolver, the tracker's and the
 * checks' credential declarations, the run assembly's child environment.
 */
class DoctorProjectReadingsSpec extends Specification {

    PipelineSource source = Mock()

    def "FR4: the law is the pipeline source's full load of the clone"() {
        when:
        def loaded = readings([:], [source: source]).loadPipeline(CLONE)

        then:
        1 * source.load(CLONE.clonePath()) >> new LoadOutcome.Loaded(definition())
        0 * source._
        loaded == definition()
    }

    def "FR4: located errors are thrown together, each rendered for the console"() {
        given:
        def errors = [
            new ConfigError('config.yaml', 'line 3', 'bad'),
            new ConfigError('stages/a.yaml', 'line 1', 'worse')
        ]
        source.load(_) >> new LoadOutcome.Invalid(errors)

        when:
        readings([:], [source: source]).loadPipeline(CLONE)

        then:
        def failure = thrown(PipelineLoadFailedException)
        failure.renderedErrors() == errors*.render()*.forConsole()
    }

    def "FR1, D3: the stage bindings are the resolver's: default #binding with overrides #stages"() {
        expect:
        readings([:], [bindings: new BindingProperties(binding, stages)]).bindings(definition(false, ['plan', 'build'])) ==
        new DoctorProjectReadings.StageBindings(container, host)

        where:
        binding | stages || container | host
        'host' | [:] || false | true
        'container' | [:] || true | false
        'container' | [build: 'host'] || true | true
    }

    def "FR1: the declared secrets are the tracker's and the checks', each once; none without a tracker"() {
        expect:
        readings().declaredSecrets(definition(true)) == [TRACKER_TOKEN]
        readings().declaredSecrets(definition()) == []
    }

    def "FR1, NFR-S1: the round's allowlist resolves through the secrets port and scrubs the declared credentials"() {
        when:
        def childEnv = readings([PATH: '/r/bin', (TRACKER_TOKEN): 'secret']).roundChildEnv(definition(true))

        then:
        childEnv.credentialNames().contains(TRACKER_TOKEN)
        childEnv.compose(HostTaskExecutionEnvironment.BASE_ENV_NAMES, [:]) == [PATH: '/r/bin']
    }

    def "FR1, FR4: the child PATH is the host environment's composition over the round's allowlist"() {
        expect:
        readings([PATH: '/r/bin']).childPath(definition()) == '/r/bin'
        readings().childPath(definition()) == null
    }
}
