package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.app.CheckRunContext
import spock.lang.Specification

/**
 * NFR-S2, D5 of add-plugin-architecture: the fixed, engine-defined whitelist of values an http check
 * may interpolate, and the substitution itself. Four variables, closed: enough to address this run's
 * result, and nothing that could carry a secret or attacker-supplied text into a URL.
 */
class HttpCheckVariablesSpec extends Specification {

    private static CheckRunContext contextOf(Map<String, String> values) {
        { name -> Optional.ofNullable(values[name]) } as CheckRunContext
    }

    private static final Map<String, String> RUN = [
        'task.id': 'PROJ-42',
        'task.branch': 'gnomish/PROJ-42',
        'stage.name': 'implement'
    ]

    def "the whitelist is exactly the four engine-defined variables"() {
        expect:
        HttpCheckVariables.WHITELIST == [
            'task.id',
            'task.branch',
            'attempt.commit',
            'stage.name'
        ] as Set
    }

    // NFR-S2: the run's own values reach the request — a branch-scoped result is addressable.
    def "every whitelisted reference resolves to this run's value"() {
        given:
        def variables = HttpCheckVariables.of(contextOf(RUN), 'c0ffee')

        expect:
        variables.resolve('https://sonar.example.com/api?b=${task.branch}&c=${attempt.commit}') ==
                'https://sonar.example.com/api?b=gnomish/PROJ-42&c=c0ffee'
        variables.resolve('${task.id}/${stage.name}') == 'PROJ-42/implement'
    }

    def "text with no reference is returned unchanged"() {
        expect:
        HttpCheckVariables.of(contextOf(RUN), 'c0ffee').resolve('https://sonar.example.com/api') ==
                'https://sonar.example.com/api'
    }

    // NFR-S2: fail closed — a URL missing its value addresses the wrong result.
    def "a whitelisted variable this run cannot supply fails the substitution, naming it"() {
        when:
        HttpCheckVariables.of(contextOf(RUN), null).resolve('https://s.example.com/${attempt.commit}')

        then:
        def e = thrown(HttpCheckVariableException)
        e.reason().contains('attempt.commit')
        e.reason().contains('cannot supply')
    }

    def "a non-whitelisted variable fails the substitution as uninterpolatable"() {
        when:
        HttpCheckVariables.of(contextOf(RUN), 'c0ffee').resolve('https://s.example.com/${env.SONAR_TOKEN}')

        then:
        def e = thrown(HttpCheckVariableException)
        e.reason().contains('env.SONAR_TOKEN')
        e.reason().contains('not an interpolatable variable')
    }

    // NFR-S2: a value is substituted literally — a '$' in a branch name is not a second reference.
    def "a substituted value is inserted literally"() {
        expect:
        HttpCheckVariables.of(contextOf(['task.branch': 'gnomish/a$1b']), null).resolve('${task.branch}') ==
        'gnomish/a$1b'
    }

    def "references are reported in appearance order, deduplicated"() {
        expect:
        HttpCheckVariables.referencesIn('${task.id}/${stage.name}/${task.id}') as List ==
                ['task.id', 'stage.name']
        HttpCheckVariables.referencesIn('https://sonar.example.com/api').isEmpty()
    }

    // The load seam grades a url in the shape it will take: ${...} is not a legal URL character.
    def "erasing references leaves a parseable url"() {
        expect:
        HttpCheckVariables.erase('https://s.example.com/${task.branch}/x') == 'https://s.example.com/x/x'
        URI.create(HttpCheckVariables.erase('https://s.example.com/${attempt.commit}')).isAbsolute()
    }

    def "the attempt commit comes from the round, not from the run context"() {
        expect:
        HttpCheckVariables.of(contextOf(RUN), 'c0ffee').resolve('${attempt.commit}') == 'c0ffee'
    }
}
