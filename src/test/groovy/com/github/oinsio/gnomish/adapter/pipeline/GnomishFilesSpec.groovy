package com.github.oinsio.gnomish.adapter.pipeline

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * GnomishFiles is the read-only filesystem-discovery seam of the loader (task 6.1,
 * FR1/FR8): given a .gnomish/ root Path it reads the raw text of config.yaml and
 * pipeline.yaml, discovers the stages/<name>/ directories deterministically, and
 * reads each stage.yaml where present — handing (name, text) pairs to the parser.
 * It never parses YAML and never writes to disk (NFR-R1). A genuinely unreadable
 * required file (config.yaml or pipeline.yaml missing) is an I/O fault and surfaces
 * as an IOException, NOT a ConfigError (design D3) — validation problems are data,
 * I/O faults are exceptions. Consistency between pipeline.yaml and the discovered
 * directories is task 6.2's concern, not this class's.
 * Implements FR1, FR8 of load-pipeline-config.
 */
class GnomishFilesSpec extends Specification {

    @TempDir
    Path root

    private void write(String relative, String text) {
        Path target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    private void stage(String name, String manifest) {
        write("stages/${name}/stage.yaml", manifest)
    }

    def "a valid tree reads config and pipeline text and every stage manifest in sorted order"() {
        given: 'a .gnomish/ tree with two stages declared out of alphabetical order'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages:\n  - implement\n  - plan\n')
        stage('plan', 'purpose: plan\n')
        stage('implement', 'purpose: build\n')

        when: 'the tree is read'
        def raw = GnomishFiles.read(root)

        then: 'the top-level file texts are read verbatim'
        raw.configText() == 'schemaVersion: "1"\n'
        raw.pipelineText() == 'stages:\n  - implement\n  - plan\n'

        and: 'stage directories are discovered deterministically, sorted by name'
        raw.stages()*.name() == ['implement', 'plan']

        and: 'each stage carries its manifest text'
        raw.stages()*.text() == [
            'purpose: build\n',
            'purpose: plan\n'
        ]
    }

    def "sortByName orders stage directories by file name regardless of input order"() {
        given: 'directory paths supplied in a deliberately non-alphabetical order'
        // A filesystem's Files.list() enumeration order is unspecified and
        // platform-dependent, so the sort is exercised directly with a controlled
        // unsorted input — the only way to pin the NFR-R1 ordering contract.
        def input = [
            Path.of('stages/review'),
            Path.of('stages/implement'),
            Path.of('stages/plan')
        ]

        when: 'the directories are sorted by name'
        def sorted = GnomishFiles.sortByName(input)

        then: 'they come back in ascending file-name order'
        sorted*.fileName*.toString() == ['implement', 'plan', 'review']
    }

    def "a stages directory whose manifest is absent yields a null-text raw stage, not an exception"() {
        given: 'the required files plus a stage directory without a stage.yaml'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages: []\n')
        Files.createDirectories(root.resolve('stages/orphan'))

        when: 'the tree is read'
        def raw = GnomishFiles.read(root)

        then: 'the orphan directory is discovered with no manifest text (6.2 decides what that means)'
        raw.stages()*.name() == ['orphan']
        raw.stages()[0].text() == null
    }

    def "an absent stages directory yields an empty stage list, not an exception"() {
        given: 'the required files but no stages/ directory at all'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages: []\n')

        when: 'the tree is read'
        def raw = GnomishFiles.read(root)

        then: 'discovery finds no stages and does not fail'
        raw.stages().isEmpty()
    }

    def "a plain file named stages is not mistaken for a stage directory"() {
        given: 'the required files and a regular file (not a directory) called stages'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages: []\n')
        Files.writeString(root.resolve('stages'), 'not a directory\n')

        when: 'the tree is read'
        def raw = GnomishFiles.read(root)

        then: 'no stages are discovered'
        raw.stages().isEmpty()
    }

    def "a non-directory entry under stages is ignored during discovery"() {
        given: 'a real stage directory alongside a stray file under stages/'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        stage('plan', 'purpose: plan\n')
        Files.writeString(root.resolve('stages/README.md'), 'notes\n')

        when: 'the tree is read'
        def raw = GnomishFiles.read(root)

        then: 'only the directory is treated as a stage'
        raw.stages()*.name() == ['plan']
    }

    def "a missing required file is an I/O fault, surfaced as IOException not a ConfigError"() {
        given: 'a tree missing one required top-level file'
        if (present == 'pipeline') {
            write('pipeline.yaml', 'stages: []\n')
        } else if (present == 'config') {
            write('config.yaml', 'schemaVersion: "1"\n')
        }

        when: 'the tree is read'
        GnomishFiles.read(root)

        then: 'an IOException names the missing required file, and it is not a ConfigError'
        IOException e = thrown()
        e.message.contains(missing)
        !(e instanceof RuntimeException)

        where:
        present    | missing
        'config'   | 'pipeline.yaml'
        'pipeline' | 'config.yaml'
        'neither'  | 'config.yaml'
    }

    def "reading does not create, modify, or delete anything under the root"() {
        given: 'a fully valid tree'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        stage('plan', 'purpose: plan\n')
        def before = snapshot(root)

        when: 'the tree is read twice'
        def first = GnomishFiles.read(root)
        def second = GnomishFiles.read(root)

        then: 'nothing on disk changed (NFR-R1: read-only)'
        snapshot(root) == before

        and: 'repeated reads are equal (NFR-R1: deterministic)'
        first == second
    }

    private static Map<String, String> snapshot(Path root) {
        Map<String, String> files = [:]
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                files.put(root.relativize(p).toString(), Files.readString(p))
            }
        }
        files
    }
}
