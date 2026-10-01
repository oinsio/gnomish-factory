package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.ProjectName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.PropertySource
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link OperatorConfigLoader}'s refusals beyond the level of a key: an operator file others may
 * write (NFR-S2), an unregistered or inside-a-clone {@code --dir} (UX2), a key outside {@code
 * factory.*} in an operator file, a removed key (FR12), a {@code factory.*} key in a source Spring loaded on its own, and
 * a malformed command line or project file — each one more line of the one report (FR7).
 *
 * <p>Implements FR3, FR7, FR12, NFR-S2, NFR-O2, UX2 of add-project-registry.
 */
class OperatorConfigRefusalsSpec extends Specification {

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    // NFR-S2: "Group-writable project file"
    def "NFR-S2: a configuration file #who may write stops startup naming the chmod"() {
        given:
        def file = harness.home.project(new ProjectName('widgets')).config()
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions))

        expect:
        violations([
            'run',
            "--dir=$widgets".toString()
        ]) == [
            "$file is writable by group or others — found in $file — a file others can write could widen the" +
            " sandbox — run: chmod go-w $file"
        ]*.toString()

        where:
        who | permissions
        'group' | 'rw-rw-r--'
        'others' | 'rw-r--rw-'
    }

    // NFR-S2: a private file passes
    def "NFR-S2: an owner-only writable file passes"() {
        given:
        harness.hostConfig('factory:\n  serve:\n    slots: 2\n')
        Files.setPosixFilePermissions(harness.home.hostConfig(), PosixFilePermissions.fromString('rw-r--r--'))

        expect:
        harness.load([
            'run',
            "--dir=$widgets".toString()
        ]).get('factory.serve.slots') == '2'
    }

    // UX2: "Unregistered clone is refused with the fix"; "Subdirectory of a clone"
    def "UX2: #shape --dir is one more violation line"() {
        given:
        def dir = shape == 'an unregistered' ? harness.gitTree('newproj') : Files.createDirectories(widgets.resolve('app'))

        when:
        def lines = violations([
            'take',
            "--dir=$dir".toString()
        ])

        then:
        lines.size() == 1
        lines[0].contains(expected(dir))

        where:
        shape << [
            'an unregistered',
            'a subdirectory of a registered clone as'
        ]
    }

    // D6: a key outside factory.* has no place in the operator files; clones: belongs in the project file
    def "D6: a foreign key in #file is refused, a project file's clones are not"() {
        given:
        if (file == 'the host file') {
            harness.hostConfig('logging:\n  level:\n    root: debug\n')
        } else {
            harness.projectConfig('widgets', 'logging:\n  level:\n    root: debug\n')
        }

        when:
        def lines = violations([
            'run',
            "--dir=$widgets".toString()
        ])

        then:
        lines.size() == 1
        lines[0].startsWith('logging.level.root does not belong in this file')
        lines[0].contains("the file holds only $holds")

        where:
        file | holds
        'the host file' | 'factory.* keys'
        'a project file' | 'clones: and factory.* keys'
    }

    // D6: a factory key in any other source — an application.yaml beside the jar — is refused
    def "D6: a factory key in a source Spring loaded on its own is refused"() {
        given:
        def beside = new MapPropertySource("Config resource 'file [application.yaml]'", ['factory.serve.slots': '9'])

        expect:
        violations([
            'run',
            "--dir=$widgets".toString()
        ], [:], [:], [beside]) == [
            "factory.serve.slots is not allowed here — found in Config resource 'file [application.yaml]'" +
            " — it is an any key, read only from factory.yaml, a project's own file or the command line" +
            " — move it to ${harness.home.projects().resolve('widgets').resolve('project.yaml')}" +
            " or ${harness.home.hostConfig()}"
        ]*.toString()
    }

    // FR7: a malformed command line or project file is reported, not thrown past the report
    def "FR7: #problem is a violation line"() {
        given:
        if (problem == 'an invalid project file') {
            harness.projectConfig('widgets', 'factory: [unclosed\n')
        }
        if (problem == 'an invalid host file') {
            harness.hostConfig('factory: [unclosed\n')
        }

        expect:
        violations(args(problem)).first().contains(expected)

        where:
        problem | expected
        'an unknown subcommand' | "'frobnicate' is not a gnomish subcommand"
        'a missing --dir' | '--dir is required'
        'an invalid project file' | 'is not valid YAML'
        'an invalid host file' | 'is not valid YAML'
    }

    // FR12: the removed key is an unknown key
    def "FR12: factory.agent-cli-env-passthrough is an unknown key"() {
        given:
        harness.hostConfig('factory:\n  agent-cli-env-passthrough:\n    - HOME\n')

        expect:
        violations(['project', 'list']) == [
            "factory.agent-cli-env-passthrough is not a known key — found in ${harness.home.hostConfig()}:3" +
            ' — no factory.* setting has this name (removed keys included)' +
            " — remove it, or correct its spelling ('gnomish project show' lists every key)"
        ]*.toString()
    }

    private String expected(Path dir) {
        dir.fileName.toString() == 'newproj'
                ? "gnomish project add newproj --dir=$dir"
                : "inside the registered clone $widgets of project widgets"
    }

    private List<String> args(String problem) {
        switch (problem) {
            case 'an unknown subcommand': return ['frobnicate']
            case 'a missing --dir': return ['status', 'task-1']
            default: return [
                'run',
                "--dir=$widgets".toString()
            ]
        }
    }

    private List<String> violations(
            List<String> args,
            Map<String, Object> variables = [:],
            Map<String, Object> systemProperties = [:],
            List<PropertySource<?>> others = []) {
        try {
            harness.load(args, variables, systemProperties, others)
            []
        } catch (ConfigurationViolationsException e) {
            e.violations()
        }
    }
}
