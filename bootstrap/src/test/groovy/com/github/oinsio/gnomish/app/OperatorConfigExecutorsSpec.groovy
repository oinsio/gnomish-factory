package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * An executor declared in operator configuration is refused at load by the loader's own key check
 * (NFR-S3, FR1 of define-executor-contract): an executor program is law, declared in
 * {@code .gnomish/} at the base-ref commit, so no operator file and no command-line option can
 * declare one. A {@code factory.executors.*} key is an unknown key wherever it is set; a top-level
 * {@code executors:} section of an operator file is a key the file does not hold. No check of its
 * own exists for executors: the operator configuration knows no executor key, and these features
 * pin that it stays so.
 *
 * <p>Implements NFR-S3, FR1 of define-executor-contract.
 */
class OperatorConfigExecutorsSpec extends Specification {

    private static final String DECLARATION = 'executors:\n  acme:\n    program:\n      - /opt/acme/bin/acme\n'
    private static final String FACTORY_BLOCK = 'factory:\n  executors:\n    acme:\n      program:\n        - /opt/acme/bin/acme\n'

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    // Scenario: pipeline/pipeline-config / Executor declared in operator config is ignored
    def "NFR-S3: a factory.executors key in the #place is the unknown-key error, and the startup is refused"() {
        given:
        List<String> args = [
            'run',
            "--dir=$widgets".toString()
        ]
        if (place == 'host file') {
            harness.hostConfig(FACTORY_BLOCK)
        } else if (place == 'project file') {
            harness.projectConfig('widgets', FACTORY_BLOCK)
        } else {
            args << '--factory.executors.acme.program=/opt/acme/bin/acme'
        }

        when:
        def lines = violations(args)

        then:
        lines.size() == 1
        lines[0].startsWith("factory.executors.acme.program is not a known key — found in ${location(place)}")
        lines[0].endsWith(' — no factory.* setting has this name (removed keys included)' +
                " — remove it, or correct its spelling ('gnomish project show' lists every key)")

        where:
        place << [
            'host file',
            'project file',
            'command line'
        ]
    }

    // Scenario: pipeline/pipeline-config / Executor declared in operator config is ignored
    def "NFR-S3: a top-level executors section in the #place is a key the file does not hold, and the startup is refused"() {
        given:
        if (place == 'host file') {
            harness.hostConfig(DECLARATION)
        } else {
            harness.projectConfig('widgets', DECLARATION)
        }

        when:
        def lines = violations([
            'run',
            "--dir=$widgets".toString()
        ])

        then:
        lines.size() == 1
        lines[0].startsWith('executors.acme.program does not belong in this file — found in ')
        lines[0].endsWith(" — the file holds only ${holds} — remove it")

        where:
        place | holds
        'host file' | 'factory.* keys'
        'project file' | 'clones: and factory.* keys'
    }

    private String location(String place) {
        if (place == 'host file') {
            return harness.home.hostConfig().toString()
        }
        place == 'project file'
                ? harness.home.projects().resolve('widgets').resolve('project.yaml').toString()
                : 'the command line (--factory.executors.acme.program)'
    }

    private List<String> violations(List<String> args) {
        try {
            harness.load(args, [:])
            []
        } catch (ConfigurationViolationsException e) {
            e.violations()
        }
    }
}
