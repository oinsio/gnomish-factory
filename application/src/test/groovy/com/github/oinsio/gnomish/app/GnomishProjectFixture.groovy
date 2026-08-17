package com.github.oinsio.gnomish.app

import java.nio.file.Files
import java.nio.file.Path

/**
 * Lays down the minimal `.gnomish/` project tree that every {@code --dir}-resolving command spec
 * in this module needs: a one-stage pipeline, that stage's manifest and instructions, and a
 * GitHub-typed {@code config.yaml}. The three specs that drive a command end-to-end from a temp
 * directory ({@code BoardCommandSpec}, {@code BoardCompositionAgreementSpec}, {@code
 * DashboardCommandSpec}) inlined the same twenty-eight lines verbatim; the only real difference
 * was whether the tracker section carries a {@code wip-limit}, which is a parameter here.
 *
 * <p>{@code tracker.type} is {@code github} purely to satisfy the seam validator's
 * registered-type + matching-subsection check via the permissive {@code TrackerValidatorStub};
 * each spec's own adapter registry decides what actually backs {@code github} for the run.
 *
 * <p>{@link #writeGnomishFile} and {@link #writePlanStage} serve the loader specs
 * ({@code PipelineStartupSpec}, {@code HeartbeatConstantsSourceSpec}) that need the same
 * one-stage tree but supply their own {@code config.yaml}.
 */
class GnomishProjectFixture {

    /** Writes the fixture with no {@code wip-limit} in the tracker section. */
    static Path writeGnomishProject(Path projectDir) {
        writeGnomishProject(projectDir, null)
    }

    /** Writes the fixture, adding {@code wip-limit} to the tracker section when non-null. */
    static Path writeGnomishProject(Path projectDir, Integer wipLimit) {
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        String wipLimitLine = wipLimit == null ? '' : "  wip-limit: ${wipLimit}\n"
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), """\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  abort-threshold: 3
${wipLimitLine}  github:
    api-url: https://api.github.com
    repo: acme/widgets
""")
        projectDir
    }

    /** Writes one file under {@code <root>/.gnomish/}, creating its parent directories. */
    static void writeGnomishFile(Path root, String relative, String text) {
        Path target = root.resolve('.gnomish').resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    /**
     * Writes the minimal one-stage {@code plan} pipeline under {@code <root>/.gnomish/}:
     * {@code pipeline.yaml}, the stage manifest and its instructions. Leaves
     * {@code config.yaml} to the caller — that is the file the loader specs vary.
     */
    static void writePlanStage(Path root) {
        writeGnomishFile(root, 'pipeline.yaml', 'stages:\n  - plan\n')
        writeGnomishFile(root, 'stages/plan/stage.yaml', '''\
purpose: plan the work
executor:
  type: agent-cli
  model: some-model
instructions: stages/plan/instructions.md
advancement: auto
''')
        writeGnomishFile(root, 'stages/plan/instructions.md', 'plan it\n')
    }
}
