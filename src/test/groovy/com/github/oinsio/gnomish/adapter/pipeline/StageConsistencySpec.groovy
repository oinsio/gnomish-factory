package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.adapter.pipeline.GnomishFiles.RawStage
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * StageConsistency is the cross-check between pipeline.yaml's declared stage-name
 * list and the stage directories discovered on disk (task 6.2, FR6). It is a pure
 * function over two lists — the pipeline names and the discovered RawStages — so it
 * is fully unit-testable without touching the filesystem; a small @TempDir test wires
 * it end-to-end through GnomishFiles.
 *
 * Two rules, both producing located ConfigErrors (NFR-O1, UX2):
 *   1. a pipeline stage without a manifest — a name in pipeline.yaml that has no
 *      stages/<name>/ directory at all, OR a directory whose stage.yaml is absent
 *      (a RawStage with null text) — located on pipeline.yaml, naming the expected
 *      manifest path stages/<name>/stage.yaml;
 *   2. a dangling stage directory — a discovered directory whose name pipeline.yaml
 *      never references — located on stages/<name>/stage.yaml, naming the unreferenced
 *      stage.
 *
 * Deterministic order: missing-manifest errors first in pipeline order, then dangling
 * errors in discovered order (NFR-R1). Stage-name emptiness/uniqueness within
 * pipeline.yaml is a different concern (StageOrderRule, task 4.2).
 * Implements FR6 of load-pipeline-config.
 */
class StageConsistencySpec extends Specification {

    private static RawStage withManifest(String name) {
        new RawStage(name, "purpose: ${name}\n")
    }

    private static RawStage withoutManifest(String name) {
        new RawStage(name, null)
    }

    private static ConfigError missingManifest(String name) {
        new ConfigError(
                'pipeline.yaml',
                "stages[${name}]",
                "pipeline stage '${name}' has no manifest; expected stages/${name}/stage.yaml".toString())
    }

    private static ConfigError dangling(String name) {
        new ConfigError(
                "stages/${name}/stage.yaml".toString(),
                "stages/${name}",
                "dangling stage directory '${name}' is not referenced by pipeline.yaml".toString())
    }

    def "an all-consistent pipeline yields no errors"() {
        given: 'every pipeline stage has a directory with a manifest, and no extra directories'
        def names = ['plan', 'implement', 'review']
        def discovered = [
            withManifest('implement'),
            withManifest('plan'),
            withManifest('review')
        ]

        expect:
        StageConsistency.check(names, discovered).isEmpty()
    }

    def "each single inconsistency yields its exact located error"() {
        when:
        def errors = StageConsistency.check(names, discovered)

        then:
        errors == [expected]

        where:
        scenario                              | names                | discovered                                        || expected
        'pipeline stage with no directory'    | ['plan']             | []                                                || missingManifest('plan')
        'pipeline stage, dir but no manifest' | ['plan']             | [withoutManifest('plan')]                         || missingManifest('plan')
        'dangling directory'                  | ['plan']             | [
            withManifest('plan'),
            withManifest('orphan')
        ]    || dangling('orphan')
    }

    def "a dangling directory that also lacks a manifest is still dangling, not missing"() {
        given: 'an unreferenced directory with no stage.yaml'
        def names = ['plan']
        def discovered = [
            withManifest('plan'),
            withoutManifest('orphan')
        ]

        expect: 'it is reported once, as dangling (rule 2), never as a missing manifest'
        StageConsistency.check(names, discovered) == [dangling('orphan')]
    }

    def "multiple mixed problems are all reported: missing-manifest first (pipeline order), then dangling (discovered order)"() {
        given: 'plan has no dir, build has a dir but no manifest, and two unreferenced dirs exist'
        def names = ['plan', 'build', 'ship']
        def discovered = [
            withManifest('ship'),
            withoutManifest('build'),
            withManifest('a-orphan'),
            withManifest('z-orphan')
        ]

        when:
        def errors = StageConsistency.check(names, discovered)

        then: 'missing manifests in pipeline order (plan, build), then dangling in discovered order'
        errors == [
            missingManifest('plan'),
            missingManifest('build'),
            dangling('a-orphan'),
            dangling('z-orphan')
        ]
    }

    def "the returned list is immutable"() {
        when:
        StageConsistency.check(['plan'], []).add(new ConfigError('x', 'y', 'z'))

        then:
        thrown(UnsupportedOperationException)
    }

    // --- end-to-end through GnomishFiles -----------------------------------

    @TempDir
    Path root

    private void write(String relative, String text) {
        Path target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    def "wired through GnomishFiles: a real tree with a missing manifest and a dangling dir reports both"() {
        given: 'pipeline names plan (dir, no manifest) and a dangling orphan dir with a manifest'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        Files.createDirectories(root.resolve('stages/plan'))
        write('stages/orphan/stage.yaml', 'purpose: orphan\n')

        when: 'the tree is read and reconciled'
        def raw = GnomishFiles.read(root)
        def errors = StageConsistency.check(['plan'], raw.stages())

        then: 'plan is a missing manifest and orphan is dangling'
        errors == [
            missingManifest('plan'),
            dangling('orphan')
        ]
    }
}
