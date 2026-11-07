package com.github.oinsio.gnomish.domain.pipeline

import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks

import java.time.Duration
import spock.lang.Specification

/**
 * VerifyCheck: the sealed model of a stage's quality-control checks — two inert-data variants
 * (design D1 of migrate-verify-to-executor-contract): the {@code Executor} check every {@code
 * builtin}, {@code command}, {@code external} and {@code executor:} entry maps to, and the
 * {@code Judge}. Local-sanity ranges (polled timing, judge votes, non-blank identifiers) are a
 * located pure-validator concern, never a constructor exception. The token-to-executor mapping
 * itself is the loader's, pinned by {@code VerifyCheckMapperSpec} and the loader specs.
 * Implements FR2 of load-pipeline-config; FR1, UX2 of migrate-verify-to-executor-contract.
 */
class VerifyCheckSpec extends Specification {

    // FR1 (migrate-verify-to-executor-contract): exhaustive switching needs the variant set
    // closed, and the three sugar tokens add no variant of their own
    def "VerifyCheck is sealed over exactly the executor check and the judge"() {
        expect: 'the interface is sealed'
        VerifyCheck.isSealed()

        and: 'its only permitted implementations are the executor check and the judge'
        VerifyCheck.permittedSubclasses*.name.toSorted() ==
                [
                    VerifyCheck.Executor.name,
                    VerifyCheck.Judge.name
                ].toSorted()
    }

    // FR1, UX2: the manifest forms an executor check may be declared with are a closed set
    def "Token is the closed set of the three sugar tokens and the explicit executor form"() {
        expect:
        VerifyCheck.Token.values()*.name() == [
            'BUILTIN',
            'COMMAND',
            'EXTERNAL',
            'EXECUTOR'
        ]
    }

    // FR7: the verdict threshold is a closed three-value enum, error first
    def "Threshold is the closed set of error, warning and note"() {
        expect:
        Threshold.values()*.name() == ['ERROR', 'WARNING', 'NOTE']
    }

    // FR13 (add-sandbox-core): the freshness knob is a closed two-value enum
    def "VerifyIn is a closed two-value enum"() {
        expect: 'exactly same-box and fresh-box, in that order'
        VerifyCheck.VerifyIn.values()*.name() == ['SAME_BOX', 'FRESH_BOX']
    }

    // FR1: the executor check carries every field of the request beside its token
    def "an executor check exposes its token, executor name, check id, settings and engine-common fields"() {
        when:
        def check = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'sonarqube', 'quality-gate',
                [project: 'acme'], Duration.ofSeconds(30), Duration.ofMinutes(15),
                VerifyCheck.TimeoutClass.INFRASTRUCTURE, ['sonar-project.properties'],
                VerifyCheck.VerifyIn.FRESH_BOX, Threshold.WARNING, ['sonar/gate'])

        then:
        check.token() == VerifyCheck.Token.EXECUTOR
        check.executorName() == 'sonarqube'
        check.checkId() == 'quality-gate'
        check.settings() == [project: 'acme']
        check.interval() == Duration.ofSeconds(30)
        check.timeout() == Duration.ofMinutes(15)
        check.timeoutClass() == VerifyCheck.TimeoutClass.INFRASTRUCTURE
        check.pinPaths() == ['sonar-project.properties']
        check.verifyIn() == VerifyCheck.VerifyIn.FRESH_BOX
        check.threshold() == Threshold.WARNING
        check.expected() == ['sonar/gate']
    }

    // FR1: the default belongs to the loader, not the model — the record carries whatever
    // executor name it is handed, including one no registry serves, so the load seam can
    // see and report it
    def "an executor check carries its executor name verbatim, defaulting nothing"() {
        expect:
        VerifyChecks.external('ci', 'nobody-serves-this', Duration.ofSeconds(30), Duration.ofMinutes(5),
                VerifyCheck.TimeoutClass.QUALITY).executorName() == 'nobody-serves-this'
    }

    // FR2: the model is immutable — settings, pin paths and expected contexts are copied
    // defensively from their sources
    def "an executor check is isolated from later mutation of its source collections"() {
        given:
        Map<String, Object> settings = [url: 'https://sonar/api']
        List<String> pins = ['ci.yml']
        List<String> expected = ['ci/build']

        when:
        def check = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'scm-status', 'ci', settings,
                Duration.ofSeconds(30), Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY, pins,
                VerifyCheck.VerifyIn.SAME_BOX, Threshold.ERROR, expected)
        settings.intruder = 'later noise'
        pins << 'later-noise'
        expected << 'later-noise'

        then:
        check.settings() == [url: 'https://sonar/api']
        check.pinPaths() == ['ci.yml']
        check.expected() == ['ci/build']
    }

    // FR2: the exposed collections themselves cannot be mutated
    def "an executor check's #field cannot be mutated"() {
        given:
        def check = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'scm-status', 'ci', [a: 1],
        Duration.ofSeconds(30), Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY, ['ci.yml'],
        VerifyCheck.VerifyIn.SAME_BOX, Threshold.ERROR, ['ci/build'])

        when:
        mutate(check)

        then:
        thrown(UnsupportedOperationException)

        where:
        field | mutate
        'settings' | { VerifyCheck.Executor c ->
            c.settings().put('intruder', true)
        }
        'pin paths' | { VerifyCheck.Executor c -> c.pinPaths() << 'intruder' }
        'expected' | { VerifyCheck.Executor c -> c.expected() << 'intruder' }
    }

    // FR11/D6: timing sanity (positivity, interval <= timeout) belongs to the pure validators
    // as a located ConfigError — the record must carry an insane value so the validator can
    // still see and report it
    def "an executor check carries insane timing (#reason) without throwing"() {
        when:
        def check = VerifyChecks.external('ci/build', 'github-actions', interval, timeout, VerifyCheck.TimeoutClass.QUALITY)

        then:
        notThrown(Exception)
        check.interval() == interval
        check.timeout() == timeout

        where:
        interval | timeout | reason
        Duration.ZERO | Duration.ofMinutes(5) | 'zero interval'
        Duration.ofSeconds(-30) | Duration.ofMinutes(5) | 'negative interval'
        Duration.ofSeconds(30) | Duration.ofSeconds(-1) | 'negative timeout'
        Duration.ofMinutes(10) | Duration.ofMinutes(5) | 'interval above timeout'
    }

    // FR1, UX2: until the executor registry runs every check (task 5.1), the engine polls every
    // external check and every check naming an executor other than the two in-process built-ins
    def "a check is polled when #reason"() {
        expect:
        new VerifyCheck.Executor(token, name, 'id', [:], Duration.ZERO, Duration.ZERO,
        VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.SAME_BOX,
        Threshold.ERROR, []).polled() == polled

        where:
        token | name | polled | reason
        VerifyCheck.Token.BUILTIN | 'files-exist' | false | 'never, for a builtin'
        VerifyCheck.Token.COMMAND | 'shell' | false | 'never, for a command'
        VerifyCheck.Token.EXTERNAL | 'github-actions' | true | 'always, for an external'
        VerifyCheck.Token.EXTERNAL | 'shell' | true | 'always, for an external, whatever it names'
        VerifyCheck.Token.EXECUTOR | 'files-exist' | false | 'an executor form names files-exist'
        VerifyCheck.Token.EXECUTOR | 'shell' | false | 'an executor form names shell'
        VerifyCheck.Token.EXECUTOR | 'sonarqube' | true | 'an executor form names any other executor'
    }

    // FR1, UX2: the provider registry and the factory.check.* subtree still know the
    // github-actions built-in by its former provider key, every other executor by its name
    def "the provider key of #name is #key"() {
        expect:
        VerifyChecks.external('ci', name, Duration.ofSeconds(30), Duration.ofMinutes(5),
                VerifyCheck.TimeoutClass.QUALITY).providerKey() == key

        where:
        name | key
        'github-actions' | 'github'
        'http' | 'http'
        'github' | 'github'
    }

    // FR2/FR11: a judge check carries its acceptance criteria, model pin,
    // opaque settings and vote count
    def "Judge exposes criteria file, model, settings and votes"() {
        when: 'a judge check is modeled'
        VerifyCheck check = new VerifyCheck.Judge(
                'stages/review/acceptance.md', 'claude-sonnet-4-5', [temperature: 0], 3)

        then: 'the variant exposes exactly the four judge fields'
        check.criteriaFile() == 'stages/review/acceptance.md'
        check.model() == 'claude-sonnet-4-5'
        check.settings() == [temperature: 0]
        check.votes() == 3
    }

    // FR11/D6: the votes rule (>= 1 and odd) belongs to the pure validators
    // (task 4.4) — the record carries an invalid count for them to report
    def "Judge carries an invalid vote count (#votes) without throwing"() {
        when: 'a judge check is modeled with votes that violate FR11'
        def check = new VerifyCheck.Judge('acceptance.md', 'claude-sonnet-4-5', [:], votes as int)

        then: 'the record carries the value untouched for the validator to report'
        notThrown(Exception)
        check.votes() == votes

        where:
        votes << [0, -1, 2] // non-positive and even counts are validator territory
    }

    // FR2: the model is immutable — defensive copy isolates from the source map
    def "Judge is isolated from later mutation of the source settings"() {
        given: 'a mutable source settings map'
        Map<String, Object> source = [temperature: 0]

        when: 'the check is created and the source map grows afterwards'
        def check = new VerifyCheck.Judge('acceptance.md', 'claude-sonnet-4-5', source, 1)
        source.intruder = 'later noise'

        then: 'the check still holds only the original settings'
        check.settings() == [temperature: 0]
    }

    // FR2: the exposed settings map itself cannot be mutated
    def "Judge's settings map is immutable"() {
        given: 'a judge check'
        def check = new VerifyCheck.Judge('acceptance.md', 'claude-sonnet-4-5', [:], 1)

        when: 'a caller tries to put into the exposed map'
        check.settings().put('intruder', true)

        then: 'the map rejects the mutation'
        thrown(UnsupportedOperationException)
    }

    // FR2: checks are plain values, so an ordered verify list compares by
    // content — order preservation itself is the stage's list
    def "checks with the same fields are equal values"() {
        expect: 'two independently constructed checks with equal fields are equal'
        VerifyChecks.builtin('files_exist', [paths: []]) == VerifyChecks.builtin('files_exist', [paths: []])
        VerifyChecks.command('make test') == VerifyChecks.command('make test')
        VerifyChecks.external('ci/build', 'github-actions', Duration.ofSeconds(30), Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY) ==
                VerifyChecks.external('ci/build', 'github-actions', Duration.ofSeconds(30), Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY)
        new VerifyCheck.Judge('acceptance.md', 'claude-sonnet-4-5', [:], 3) ==
        new VerifyCheck.Judge('acceptance.md', 'claude-sonnet-4-5', [:], 3)

        and: 'the token is part of the value: the same executor and id under two tokens differ'
        VerifyChecks.command('make test') !=
                new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'shell', 'make test', [:], Duration.ZERO,
                Duration.ZERO, VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.SAME_BOX,
                Threshold.ERROR, [])
    }
}
