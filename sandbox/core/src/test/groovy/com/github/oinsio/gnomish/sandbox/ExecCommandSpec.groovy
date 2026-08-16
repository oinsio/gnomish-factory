package com.github.oinsio.gnomish.sandbox

import spock.lang.Specification

/**
 * ExecCommand: the exec request carried through TaskExecutionEnvironment.exec —
 * argv, the factory-set environment fragment, optional stdin, and the
 * merge-stderr switch (design D1; FR1, FR9, FR24 of add-sandbox-core).
 *
 * Added with the `:sandbox:core` extraction (FR8, task 3.1 of
 * split-into-modules): the record's own contract used to be covered only
 * incidentally, by adapter specs that now live in a different module — a
 * module's classes must be verified by that module's own specs (FR11, design D6).
 *
 * FR1: argv is required; FR9/FR24: env and stdin travel with the request.
 */
class ExecCommandSpec extends Specification {

    // FR1: a process needs at least a binary — an empty argv is refused, not run
    def "an empty command is rejected"() {
        when: 'a command with no argv is built'
        new ExecCommand([], [:], null, false)

        then: 'construction fails naming the component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ExecCommand.command must not be empty')
    }

    // FR1: a non-empty argv is accepted and exposed in order
    def "a non-empty command is accepted and exposed in argv order"() {
        expect: 'the argv survives construction unchanged'
        new ExecCommand(['git', 'status'], [:], null, false).command() == ['git', 'status']
    }

    // FR1/FR9/FR24: `of` is the plainest form — no env, no stdin, separate stderr
    def "of builds the plainest form"() {
        given: 'the plainest command'
        def command = ExecCommand.of(['git', 'status'])

        expect: 'argv is carried and every other component is at its plain default'
        command.command() == ['git', 'status']
        command.env() == [:]
        command.stdin() == null
        !command.mergeStderr()
    }

    // FR9/FR24: the factory-set env fragment and stdin content are carried as given
    def "env, stdin and mergeStderr are carried as given"() {
        given: 'a fully specified command'
        def command = new ExecCommand(['claude'], [GNOMISH_STAGE: 'build'], 'the prompt', true)

        expect: 'each component is exposed unchanged'
        command.env() == [GNOMISH_STAGE: 'build']
        command.stdin() == 'the prompt'
        command.mergeStderr()
    }

    // FR1: argv and env are defensively copied and exposed immutable
    def "argv and env are defensively copied and exposed immutable"() {
        given: 'mutable sources'
        def argv = ['git', 'status']
        def env = [GNOMISH_STAGE: 'build']

        when: 'the command is built and the sources change afterwards'
        def command = new ExecCommand(argv, env, null, false)
        argv.add('--porcelain')
        env.put('INTRUDER', 'x')

        then: 'the command holds the original values'
        command.command() == ['git', 'status']
        command.env() == [GNOMISH_STAGE: 'build']

        when: 'a caller tries to mutate the exposed argv'
        command.command().add('--porcelain')

        then: 'the list rejects the mutation'
        thrown(UnsupportedOperationException)

        when: 'a caller tries to mutate the exposed env'
        command.env().put('INTRUDER', 'x')

        then: 'the map rejects the mutation'
        thrown(UnsupportedOperationException)
    }
}
