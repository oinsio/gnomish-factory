package com.github.oinsio.gnomish.distribution

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * A re-run converges a draft left by a killed release job (NFR-R2 of add-release-pipeline,
 * design D6). {@code gh release create} deletes its own draft when an upload or the publish fails,
 * but a job killed between the two leaves the draft behind, and the next create would add a second
 * one beside it: its pre-check sees published releases only. The workflow therefore removes a
 * draft under the tag before it publishes — the draft only, never the tag, which is the one record
 * of the version (design D1).
 */
class ReleaseDraftCleanupSpec extends Specification {

    private static final String CLEANUP = 'Remove a draft left by an interrupted run'

    private static final String PUBLISH = 'Publish the release'

    def "NFR-R2: a leftover draft is removed before the release is published"() {
        given:
        def names = steps().collect { it.path('name').asText() }

        expect:
        names.contains(CLEANUP)
        names.indexOf(CLEANUP) == names.indexOf(PUBLISH) - 1
    }

    def "NFR-R2: only a release answering isDraft=true is deleted"() {
        given:
        def run = step(CLEANUP).path('run').asText()

        expect:
        run.contains('--json isDraft --jq .isDraft')
        run.contains("= 'true' ]")
        run.contains('gh release delete "$TAG" --yes')
    }

    def "D1: no step of the release deletes the tag"() {
        expect:
        steps().every {
            !it.path('run').asText().contains('--cleanup-tag')
        }
    }

    private static JsonNode step(String name) {
        steps().find { it.path('name').asText() == name }
    }

    private static List<JsonNode> steps() {
        def workflow = RepoSourceTree.repoRoot().resolve('.github/workflows/release.yml').toFile()
        new YAMLMapper().readTree(workflow).path('jobs').path('release').path('steps').toList()
    }
}
