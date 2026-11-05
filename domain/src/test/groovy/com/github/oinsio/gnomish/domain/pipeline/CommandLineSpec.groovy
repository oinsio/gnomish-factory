package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * {@link CommandLine}: the command a {@code command} stage runs ({@code executor.command}), carried
 * as its own value type beside {@code model} and {@code name} rather than a third {@code String}
 * (the transposition hazard of {@code process-invariants.md}).
 * Implements FR1, FR2 of add-command-executor.
 */
class CommandLineSpec extends Specification {

    // FR2: the declared command is carried exactly as written — no trimming, no parsing
    def "FR2: a command line exposes the declared command untouched"() {
        expect:
        new CommandLine('./gradlew generateSources').value() == './gradlew generateSources'
        new CommandLine('  make  ').value() == '  make  '
    }

    // FR2: a command line that names no command carries no mechanism; blankness of the manifest
    //     value is reported as a located error before a CommandLine is ever built
    def "FR2: a blank command (#reason) is refused"() {
        when:
        new CommandLine(value)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'CommandLine.value must not be blank'

        where:
        value | reason
        '' | 'empty'
        '   ' | 'whitespace only'
        '\t\n' | 'control whitespace only'
    }

    // FR2: a command line is a plain value, compared by content
    def "FR2: command lines with the same command are equal values"() {
        expect:
        new CommandLine('make') == new CommandLine('make')
        new CommandLine('make') != new CommandLine('make all')
    }

    // FR1, FR2: the Mechanism carries the command of a command stage; the shorter constructors
    //     carry none, so every other executor type has the command absent
    def "FR1: the Mechanism carries a command stage's command, defaulting to none"() {
        given:
        def command = new CommandLine('./gradlew generateSources')

        expect:
        new StageDefinition.Executor(ExecutorType.COMMAND, '', [:], Sandbox.none(), null, command)
        .command() == command
        new StageDefinition.Executor(ExecutorType.PROGRAM, '', [:], Sandbox.none(), 'acme').command() == null
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:], Sandbox.none()).command() == null
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]).command() == null
    }
}
