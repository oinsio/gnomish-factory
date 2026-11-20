package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.httpegress.EgressAllowlist
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.oinsio.gnomish.sandbox.SandboxEgress
import java.nio.file.Path
import org.springframework.boot.context.properties.bind.Binder
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The operator egress block, {@code factory.egress} (FR17 of migrate-verify-to-executor-contract,
 * design D16): its two scopes set in a project file reach their two guards as the lists the
 * operator wrote — through the production loader and Spring's Binder — and the two lists that
 * carried the same question before are removed keys whose violation names the scope replacing
 * each; an entry a scope cannot take is a violation located at its file and line.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract; FR5 of add-project-registry.
 */
class OperatorEgressBlockSpec extends Specification {

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    // FR17: each scope binds as the list written, and reaches its own guard only
    // Scenario: operator/operator-configuration / The two scopes are read from the block
    // Scenario: verification/factory-egress-allowlist / Allowlist lives in operator config, not the manifest
    def "FR17: the sandbox and in-process scopes bind from the project file, each to its own guard"() {
        given:
        harness.projectConfig('widgets', '''\
            factory:
              egress:
                sandbox: [api.anthropic.com, '*.pypi.example']
                in-process: [sonar.example.com, loopback]
            '''.stripIndent())

        when:
        def properties = bound()

        then: 'the box guard admits the sandbox scope, and the in-process scope does not reach it'
        new SandboxEgress(properties.egress().sandbox()).entries() == [
            'api.anthropic.com',
            '*.pypi.example'
        ]

        and: 'the in-process guard reads the in-process scope, and a sandbox host does not reach it'
        properties.egress().inProcess() == [
            'sonar.example.com',
            'loopback'
        ]
        EgressAllowlist.of(new OperatorEgress(properties.egress().inProcess()))
                .refuse(URI.create('https://api.anthropic.com/v1')).reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
    }

    // FR17: an unset block is both scopes empty — default-deny for the box and the factory process
    def "FR17: an unset egress block binds both scopes empty"() {
        expect:
        bound().egress().sandbox().isEmpty()
        bound().egress().inProcess().isEmpty()
    }

    // FR17: the two former lists are removed keys naming the scope that replaces each
    // Scenario: operator/operator-configuration / A removed key names its scope
    // Scenario: verification/factory-egress-allowlist / A removed allowlist key names its replacement
    def "FR17: #removed in a project file is a removed key naming the file, the line and #replacement"() {
        given:
        harness.projectConfig('widgets', yaml)

        expect:
        violations() == [
            "${removed} is a removed key — found in ${projectFile()}:${line(at)} — the factory's egress lists are one block" +
            " now, factory.egress, and this list is its ${replacement} scope — move the list to ${replacement}"
        ]*.toString()

        where:
        removed | replacement | at | yaml
        'factory.sandbox.egress-allowlist' | 'factory.egress.sandbox' | 4 | 'factory:\n  sandbox:\n    egress-allowlist:\n      - example.com\n'
        'factory.check.http.allowlist' | 'factory.egress.in-process' | 5 | 'factory:\n  check:\n    http:\n      allowlist:\n        - example.com\n'
    }

    // FR17: a key removed with no replacement keeps its unknown-key message
    def "FR17: a removed key with no replacement is still reported as an unknown key"() {
        given:
        harness.projectConfig('widgets', 'factory:\n  agent-cli-env-passthrough: X\n')

        expect:
        violations().size() == 1
        violations()[0].startsWith('factory.agent-cli-env-passthrough is not a known key')
    }

    // FR17: the sandbox scope takes the subset the box guard can apply
    // Scenario: sandbox/sandbox-egress / The sandbox scope refuses what the box guard cannot apply
    def "FR17: '#entry' in factory.egress.sandbox is a located violation naming the scope and the entry"() {
        given:
        harness.projectConfig('widgets', "factory:\n  egress:\n    sandbox:\n      - api.example.com\n      - '${entry}'\n")

        expect:
        violations() == [
            "factory.egress.sandbox entry '${entry}' cannot be applied by the box guard — found in ${projectFile()}:${line(5)}" +
            " — the box guard matches names only — an exact host, an IP literal or a *.suffix wildcard, never a" +
            " CIDR block or an address-class word — name the hosts the box may reach, or move the entry to" +
            " factory.egress.in-process if the factory process is the one that calls them"
        ]*.toString()

        where:
        entry << ['10.0.0.0/8', 'private']
    }

    // FR17: the in-process scope takes the full grammar, and refuses what is no entry at all
    def "FR17: the in-process scope accepts the full grammar and refuses '#entry' located at its line"() {
        given:
        harness.projectConfig('widgets', "factory:\n  egress:\n    in-process:\n      - 10.0.0.0/8\n      - private\n      - '${entry}'\n")

        expect:
        violations() == [
            "factory.egress.in-process entry '${entry}' is not an egress entry — found in ${projectFile()}:${line(6)}" +
            " — an entry is an exact host, a *.suffix wildcard, an IP literal, a CIDR block or the class word" +
            " loopback or private — never a URL, a scheme or a port — correct the entry"
        ]*.toString()

        where:
        entry << [
            'https://sonar.example.com',
            'sonar.example.com:443'
        ]
    }

    // FR17: an in-process list on the command line is judged entry by entry
    def "FR17: a comma-separated in-process list on the command line is judged entry by entry"() {
        expect:
        violations([
            '--factory.egress.in-process=sonar.example.com,a b'
        ]) == [
            "factory.egress.in-process entry 'a b' is not an egress entry — found in the command line" +
            " (--factory.egress.in-process) — an entry is an exact host, a *.suffix wildcard, an IP literal, a" +
            " CIDR block or the class word loopback or private — never a URL, a scheme or a port — correct the entry"
        ]
    }

    private FactoryProperties bound() {
        def loaded = harness.load([
            'status',
            "--dir=$widgets".toString()
        ])
        Binder.get(loaded.environment).bindOrCreate('factory', FactoryProperties)
    }

    /**
     * The line of the project file a line of the block written by {@code projectConfig} lands on:
     * the registry's own two lines ({@code clones:} and the clone) come first.
     */
    private static int line(int ofBlock) {
        2 + ofBlock
    }

    private Path projectFile() {
        harness.home.projects().resolve('widgets').resolve('project.yaml')
    }

    private List<String> violations(List<String> options = []) {
        try {
            harness.load([
                'status',
                "--dir=$widgets".toString()
            ] + options)
            []
        } catch (ConfigurationViolationsException e) {
            e.violations()
        }
    }
}
