package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Loading is a pure read: it parses and validates, and does nothing else. No execution
 * (NFR-S1) — no command, model, or external check is ever run; no writes (NFR-R1) — the
 * tree on disk is byte-for-byte unchanged and the same tree loads to an equal outcome
 * every time. With nothing executed, loading makes no model or network call, so it costs
 * zero tokens (NFR-C1).
 *
 * <p>Implements NFR-S1, NFR-R1, NFR-C1 of load-pipeline-config.
 */
class PipelineLoaderPuritySpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    def "loading is deterministic: the same tree yields an equal outcome twice (NFR-R1)"() {
        given:
        writeValidTree()

        expect:
        loadTree() == loadTree()
    }

    def "loading never creates, modifies, or deletes anything under the root (NFR-R1)"() {
        given: 'a valid tree and a snapshot of every file before loading'
        writeValidTree()
        def before = snapshot()

        when:
        loadTree()

        then: 'the tree is byte-for-byte unchanged'
        snapshot() == before
    }

    def "loading executes no configured command (NFR-S1, NFR-C1): a destructive command leaves no trace"() {
        // NFR-C1: with no command, model, or external check ever run, loading
        // makes no model or network call — zero token cost.
        given: 'a stage whose command would create a sentinel file if it ever ran'
        def sentinel = root.resolve('sentinel.txt')
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 1\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/instructions.md', 'plan\n')
        write('stages/plan/stage.yaml', """\
purpose: plan
executor:
  type: agent-cli
  model: m
instructions: stages/plan/instructions.md
verify:
  - type: command
    command: touch ${sentinel}
advancement: auto
""")

        when: 'the tree loads successfully'
        def outcome = loadTree()

        then: 'the command was carried as inert data, not executed — the sentinel never appeared'
        outcome instanceof LoadOutcome.Loaded
        !Files.exists(sentinel)
    }
}
