package com.github.oinsio.gnomish.domain.pipeline

import java.time.Duration
import spock.lang.Specification

/**
 * VerifyCheck: the sealed model of a stage's quality-control checks (design D5)
 * — four inert-data variants (Builtin, Command, External, Judge), each carrying
 * only its own fields, never executed (NG1). Local-sanity ranges (external
 * timing, judge votes, non-blank identifiers) are a located pure-validator
 * concern (design D6, task 4.4), never a constructor exception.
 * Implements FR2 of load-pipeline-config.
 */
class VerifyCheckSpec extends Specification {

    // D5/FR2: exhaustive switching needs the variant set to be closed
    def "VerifyCheck is sealed over exactly the four check types"() {
        expect: 'the interface is sealed'
        VerifyCheck.isSealed()

        and: 'its only permitted implementations are the four contract check types'
        VerifyCheck.permittedSubclasses*.name.toSorted() ==
                [
                    VerifyCheck.Builtin.name,
                    VerifyCheck.Command.name,
                    VerifyCheck.External.name,
                    VerifyCheck.Judge.name
                ].toSorted()
    }

    // FR2: a builtin check is an engine check name plus opaque declarative params
    def "Builtin exposes the engine check name and its declarative params"() {
        when: 'a builtin check is modeled with a name and params'
        VerifyCheck check = new VerifyCheck.Builtin('files_exist', [paths: ['README.md']])

        then: 'the variant exposes exactly the name and params'
        check.name() == 'files_exist'
        check.params() == [paths: ['README.md']]
    }

    // FR2: params are opaque data, so a param-free builtin check is legal
    def "Builtin accepts empty params"() {
        expect: 'a builtin check with no params carries an empty map'
        new VerifyCheck.Builtin('workspace_clean', [:]).params().isEmpty()
    }

    // FR2: the model is immutable — defensive copy isolates from the source map
    def "Builtin is isolated from later mutation of the source params"() {
        given: 'a mutable source params map'
        def source = [paths: ['README.md']]

        when: 'the check is created and the source map grows afterwards'
        def check = new VerifyCheck.Builtin('files_exist', source)
        source.intruder = 'later noise'

        then: 'the check still holds only the original params'
        check.params() == [paths: ['README.md']]
    }

    // FR2: the exposed params map itself cannot be mutated
    def "Builtin's params map is immutable"() {
        given: 'a builtin check'
        def check = new VerifyCheck.Builtin('files_exist', [paths: []])

        when: 'a caller tries to put into the exposed map'
        check.params().put('intruder', true)

        then: 'the map rejects the mutation'
        thrown(UnsupportedOperationException)
    }

    // FR2: a command check is just the executable command line
    // (contract: exit code 0 = pass — semantics live in the stage engine, not here)
    def "Command exposes the executable command line"() {
        when: 'a command check is modeled'
        VerifyCheck check = new VerifyCheck.Command('./gradlew check')

        then: 'the variant exposes exactly the command line'
        check.command() == './gradlew check'
    }

    // FR2/FR11: an external check carries its identifier and polling timing
    def "External exposes the check identifier, poll interval and timeout"() {
        when: 'an external check is modeled'
        VerifyCheck check = new VerifyCheck.External(
                'ci/build', Duration.ofSeconds(30), Duration.ofMinutes(15))

        then: 'the variant exposes exactly the identifier and both durations'
        check.checkId() == 'ci/build'
        check.interval() == Duration.ofSeconds(30)
        check.timeout() == Duration.ofMinutes(15)
    }

    // FR11/D6: timing sanity (positivity, interval <= timeout) belongs to the
    // pure validators (task 4.4) as a located ConfigError — the record must
    // carry an insane value so the validator can still see and report it
    def "External carries insane timing (#reason) without throwing"() {
        when: 'an external check is modeled with timing that violates FR11'
        def check = new VerifyCheck.External('ci/build', interval, timeout)

        then: 'the record carries the values untouched for the validator to report'
        notThrown(Exception)
        check.interval() == interval
        check.timeout() == timeout

        where:
        interval                | timeout                 | reason
        Duration.ZERO           | Duration.ofMinutes(5)   | 'zero interval'
        Duration.ofSeconds(-30) | Duration.ofMinutes(5)   | 'negative interval'
        Duration.ofSeconds(30)  | Duration.ofSeconds(-1)  | 'negative timeout'
        Duration.ofMinutes(10)  | Duration.ofMinutes(5)   | 'interval above timeout'
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
        def source = [temperature: 0]

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
    // content — order preservation itself is the stage's list (task 3.5)
    def "checks with the same fields are equal values"() {
        expect: 'two independently constructed checks with equal fields are equal'
        new VerifyCheck.Builtin('files_exist', [paths: []]) ==
        new VerifyCheck.Builtin('files_exist', [paths: []])
        new VerifyCheck.Command('make test') == new VerifyCheck.Command('make test')
        new VerifyCheck.External('ci/build', Duration.ofSeconds(30), Duration.ofMinutes(5)) ==
                new VerifyCheck.External('ci/build', Duration.ofSeconds(30), Duration.ofMinutes(5))
        new VerifyCheck.Judge('acceptance.md', 'claude-sonnet-4-5', [:], 3) ==
        new VerifyCheck.Judge('acceptance.md', 'claude-sonnet-4-5', [:], 3)
    }
}
