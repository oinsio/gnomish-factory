package com.github.oinsio.gnomish.distribution

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * A tag push starts no check workflow (FR2, NFR-C1 of add-release-pipeline; design D6): every
 * workflow under {@code .github/workflows/} that runs on {@code push} carries a {@code branches}
 * filter, so pushing {@code v0.1.0} starts the release alone. Without it, GitHub runs the
 * workflow for the tag ref too: a second 20-minute {@code check} of a commit CI already verified,
 * and a fresh in-progress run beside the green {@code main} one that the release preflight judges.
 * {@code release.yml} is the one workflow a tag is meant to start.
 */
class CheckWorkflowTriggerSpec extends Specification {

    private static final Path WORKFLOWS = RepoSourceTree.repoRoot().resolve('.github/workflows')

    /** The four check workflows the change narrowed; the scan must reach every one of them. */
    private static final List<String> CHECK_WORKFLOWS = [
        'ci.yml',
        'license-gate.yml',
        'osv-scan.yml',
        'gitleaks.yml'
    ]

    def "FR2: every push-triggered workflow except release.yml carries a branches filter"() {
        given:
        def pushTriggered = workflows().findAll { name, on ->
            pushes(on) && name != 'release.yml'
        }

        expect: 'the scan reached the check workflows'
        pushTriggered.keySet().containsAll(CHECK_WORKFLOWS)

        and:
        def unfiltered = pushTriggered.findAll { name, on ->
            !on.path('push').has('branches')
        }.keySet()
        unfiltered.isEmpty()
    }

    def "FR2: the branches filter of every check workflow matches every branch"() {
        expect:
        CHECK_WORKFLOWS.every { name ->
            workflows()[name].path('push').path('branches').collect {
                it.asText()
            } == ['**']
        }
    }

    def "FR1: release.yml runs on version tags only"() {
        given:
        def push = workflows()['release.yml'].path('push')

        expect:
        push.path('tags').collect { it.asText() } == ['v*']
        !push.has('branches')
        workflows()['release.yml'].size() == 1
    }

    /** {@code push} as a key, a bare {@code on: push}, or an entry of {@code on: [push, …]}. */
    private static boolean pushes(JsonNode on) {
        on.has('push') || on.asText() == 'push' || (on.isArray() && on.any {
            it.asText() == 'push'
        })
    }

    /** Workflow file name → its {@code on:} node. */
    private static Map<String, JsonNode> workflows() {
        def mapper = new YAMLMapper()
        Files.list(WORKFLOWS).withCloseable { files ->
            files.filter { it.fileName.toString() ==~ /.*\.ya?ml/ }
            .toList()
            .collectEntries {
                [(it.fileName.toString()): mapper.readTree(it.toFile()).path('on')]
            }
        }
    }
}
