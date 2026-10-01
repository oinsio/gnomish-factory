package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.ProjectName
import java.nio.file.Path
import spock.lang.Specification

/**
 * {@code gnomish project}'s argument parser, {@link ProjectArgumentsParser}: the three verbs, their
 * operands, and the refusals of a missing or unknown verb, a wrong operand count and an invalid
 * project name. The option contract shared with every subcommand is {@link CliArgumentsContractSpec}'s.
 *
 * <p>Implements FR2, FR4 of add-project-registry.
 */
class ProjectArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    def parser = new ProjectArgumentsParser()

    // FR2: add takes a name and the clone directory
    def "add reads the project name and resolves --dir"() {
        when:
        def parsed = parser.parse(args('project', 'add', 'widgets', '--dir=/src/widgets/.'))

        then:
        parsed == new ProjectArguments(ProjectArguments.Verb.ADD, new ProjectName('widgets'), Path.of('/src/widgets'))
    }

    // FR4: list takes nothing; show takes an optional name and never resolves --dir itself (D9)
    def "list and show read their optional name and no directory"() {
        expect:
        parser.parse(args(raw as String[])) == expected

        where:
        raw || expected
        ['project', 'list'] || new ProjectArguments(ProjectArguments.Verb.LIST, null, null)
        ['project', 'show'] || new ProjectArguments(ProjectArguments.Verb.SHOW, null, null)
        [
            'project',
            'show',
            '--dir=/src/widgets'
        ] || new ProjectArguments(ProjectArguments.Verb.SHOW, null, null)
        ['project', 'show', 'widgets'] || new ProjectArguments(ProjectArguments.Verb.SHOW, new ProjectName('widgets'), null)
    }

    // FR2, FR4: every malformed form is a usage error naming what is wrong
    def "a malformed project command is refused: #raw"() {
        when:
        parser.parse(args(raw as String[]))

        then:
        def e = thrown(UsageException)
        e.message.startsWith(message)

        where:
        raw || message
        ['project'] || 'a project verb is required: gnomish project add <name> --dir=<clone>, gnomish project list, or gnomish project show [<name>]'
        [
            'project',
            'remove',
            'widgets'
        ] || "'remove' is not a project verb: accepted forms are gnomish project add"
        ['project', 'add'] || 'a project name is required: gnomish project add <name> --dir=<clone>'
        [
            'project',
            'add',
            'widgets',
            'extra'
        ] || "unexpected argument 'extra' for 'gnomish project add'"
        ['project', 'list', 'widgets'] || "unexpected argument 'widgets' for 'gnomish project list'"
        [
            'project',
            'show',
            'widgets',
            'extra'
        ] || "unexpected argument 'extra' for 'gnomish project show'"
        ['project', 'add', 'Widgets'] || "invalid project name 'Widgets': a project name must match [a-z0-9][a-z0-9._-]*"
        ['project', 'show', '-x'] || "invalid project name '-x'"
        [
            'project',
            'list',
            '--clone=x'
        ] || "unknown option --clone for 'gnomish project'; accepted: --dir"
    }
}
