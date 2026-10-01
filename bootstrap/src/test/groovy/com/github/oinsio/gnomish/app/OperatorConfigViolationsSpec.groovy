package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.ProjectName
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link OperatorConfigLoader}'s checks: every key where its level admits it (the 16-cell level ×
 * source matrix, M3), and every violation collected into one report printed once and thrown with
 * the usage-error exit code (FR6, FR7, NFR-S1, NFR-O2, UX1). The other refusals are
 * {@code OperatorConfigRefusalsSpec}'s.
 *
 * <p>Implements FR6, FR7, NFR-S1, NFR-R1, NFR-O2, M3, UX1 of add-project-registry.
 */
class OperatorConfigViolationsSpec extends Specification {

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    // M3, FR6, FR7: one key of each level, placed in each of the four places an operator can set it
    def "M3: a #level key in the #place is #verdict"(
            String level, String key, String value, String place, boolean accepted, String verdict) {
        given:
        Map<String, Object> variables = [:]
        List<String> args = [
            'run',
            "--dir=$widgets".toString()
        ]
        switch (place) {
            case 'host file': harness.hostConfig("$key: $value\n"); break
            case 'project file': harness.projectConfig('widgets', "$key: $value\n"); break
            case 'command line': args << "--$key=$value".toString(); break
            case 'environment': variables.put(key.toUpperCase().replace('.', '_').replace('-', '_'), value); break
        }

        when:
        def loaded = load(args, variables)

        then:
        (loaded != null) == accepted
        !accepted || loaded.get(key) == value

        where:
        [
            level,
            key,
            value,
            place,
            accepted,
            verdict
        ] << matrix()
    }

    private static List<List<Object>> matrix() {
        def keys = [
            host: [
                'factory.docker-command-timeout',
                '3m'
            ],
            project: [
                'factory.sandbox.project-id',
                'widgets-id'
            ],
            any: ['factory.serve.slots', '3'],
            'sandbox-boundary': [
                'factory.sandbox.image',
                'img:1'
            ],
        ]
        def admits = [
            'host file': ['host', 'any'],
            'project file': [
                'project',
                'any',
                'sandbox-boundary'
            ],
            'command line': ['host', 'project', 'any'],
            environment: [],
        ]
        List<List<Object>> rows = []
        keys.each { level, keyValue ->
            admits.each { place, levels ->
                rows << ([
                    level,
                    keyValue[0],
                    keyValue[1],
                    place,
                    level in levels,
                    level in levels ? 'accepted' : 'refused'
                ] as List<Object>)
            }
        }
        rows
    }

    // UX1: "A boundary key in the host file"
    def "UX1: a boundary key in the host file names the file, line and the project files it belongs in"() {
        given:
        harness.hostConfig('factory:\n  sandbox:\n    egress-allowlist:\n      - example.com\n')

        expect:
        violations(['project', 'list']) == [
            "factory.sandbox.egress-allowlist is not allowed here — found in ${harness.home.hostConfig()}:4" +
            " — it is a sandbox-boundary key, read only from a project's own file — move it to" +
            " ${harness.home.projects().resolve('<name>').resolve('project.yaml')} (registered: widgets)"
        ]*.toString()
    }

    // FR6: "A boundary key on the command line"
    def "FR6: a boundary key on the command line (#form) is read only from the resolved project's file"() {
        expect:
        violations([
            'take',
            "--dir=$widgets".toString()
        ] + option, [:], property) == [
            "factory.bindings.default is not allowed here — found in the command line ($form)" +
            " — it is a sandbox-boundary key, read only from a project's own file — move it to" +
            " ${harness.home.project(new ProjectName('widgets')).config()}"
        ]*.toString()

        where:
        form | option | property
        '--factory.bindings.default' | [
            '--factory.bindings.default=host'
        ] | [:]
        '-Dfactory.bindings.default' | [] | ['factory.bindings.default': 'host']
    }

    // FR6: "A host key in a project file"
    def "FR6: a host key in a project file names factory.yaml and the command line"() {
        given:
        harness.projectConfig('widgets', 'factory:\n  docker-command-timeout: 1m\n')

        when:
        def lines = violations([
            'run',
            "--dir=$widgets".toString()
        ])

        then:
        lines.size() == 1
        lines[0].contains('it is a host key, read only from factory.yaml or the command line')
        lines[0].endsWith("move it to ${harness.home.hostConfig()}")
    }

    // FR7: "Several violations reported together"; printed once, exit code 2
    def "FR7: every violation is reported in one report, printed once to standard error"() {
        given:
        harness.hostConfig('factory:\n  sandbox:\n    image: img:1\n  git-netwrok-timeout: 5m\n')

        when:
        harness.load([
            'take',
            "--dir=$widgets".toString()
        ], [FACTORY_SERVE_SLOTS: '4'])

        then:
        def refused = thrown(ConfigurationViolationsException)
        refused.exitCode == 2
        refused.violations().size() == 3
        refused.violations()[0].startsWith('factory.sandbox.image is not allowed here')
        refused.violations()[1].startsWith('factory.git-netwrok-timeout is not a known key')
        refused.violations()[2].startsWith('environment variable FACTORY_SERVE_SLOTS is not allowed')
        harness.console.printed == [refused.message + '\n']
    }

    // FR7: "Environment variable is refused with the equivalent line"
    def "FR7: a FACTORY_* variable is refused with the equivalent file line and command-line form"() {
        expect:
        violations([
            'serve',
            "--dir=$widgets".toString()
        ], [FACTORY_SERVE_SLOTS: '4']) == [
            'environment variable FACTORY_SERVE_SLOTS is not allowed — found in the environment' +
            ' — factory.* settings are not read from environment variables' +
            " — set 'factory.serve.slots: 4' in ${harness.home.projects().resolve('widgets').resolve('project.yaml')}" +
            " or ${harness.home.hostConfig()}, or pass --factory.serve.slots=4"
        ]*.toString()
    }

    private OperatorConfigLoaderHarness.Loaded load(List<String> args, Map<String, Object> variables) {
        try {
            harness.load(args, variables)
        } catch (ConfigurationViolationsException ignored) {
            null
        }
    }

    private List<String> violations(
            List<String> args, Map<String, Object> variables = [:], Map<String, Object> systemProperties = [:]) {
        try {
            harness.load(args, variables, systemProperties)
            []
        } catch (ConfigurationViolationsException e) {
            e.violations()
        }
    }
}
