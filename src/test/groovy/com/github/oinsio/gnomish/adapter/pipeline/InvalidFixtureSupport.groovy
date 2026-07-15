package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared fixture builder for the invalid-fixture battery (task 7.2, success metric
 * M2). Each battery spec mixes this in, calls {@link #writeValidBaseline} to lay
 * down a minimal, genuinely valid single- or two-stage {@code .gnomish/} tree under
 * a {@code @TempDir}, then a data-table row mutates exactly the one file that
 * isolates one validation rule. Building trees programmatically keeps every case
 * self-contained and readable and avoids committing a large invalid tree (design
 * D8, @TempDir strategy).
 *
 * <p>The baseline is deliberately the smallest valid tree that still lets a target
 * rule run: config + pipeline + one {@code plan} stage whose {@code instructions.md}
 * exists (so {@code ReferencedFiles} stays silent) and which declares no outputs
 * (so {@code ArtifactGraphRule} stays silent) — a row that needs outputs, a second
 * stage, or a judge criteria file writes them itself. This means a minimal tree
 * isolating one rule does not accidentally trip another tier first (the task's
 * tier-interaction constraint).
 *
 * <p>M2 / UX1 / UX2 of load-pipeline-config.
 */
trait InvalidFixtureSupport {

    abstract Path getRoot()

    void write(String relative, String text) {
        Path target = getRoot().resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    /**
     * Writes a minimal valid tree: {@code config.yaml} (schemaVersion 1, default
     * attempt limit 2), a {@code pipeline.yaml} with the single {@code plan} stage,
     * and the {@code plan} manifest plus its {@code instructions.md}. The plan stage
     * declares no outputs so it stays DAG- and referenced-file-clean; a row adds
     * whatever it needs on top.
     */
    void writeValidBaseline() {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', planManifest())
        write('stages/plan/instructions.md', 'plan it\n')
    }

    /** The minimal valid {@code plan} manifest: no outputs, one command check, api executor. */
    String planManifest() {
        '''\
purpose: plan the work
executor:
  type: api
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: command
    command: echo ok
advancement: auto
'''
    }

    /** Loads the built tree and asserts the outcome is Invalid, returning its error list. */
    List<ConfigError> loadInvalid() {
        def outcome = PipelineLoader.load(getRoot())
        assert outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors()
    }
}
