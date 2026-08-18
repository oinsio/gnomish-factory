package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome

/**
 * Shared fixture for the {@code PipelineLoader} spec family (task 6.5, FR1/FR8 of
 * load-pipeline-config). The loader is the composition point of the whole capability,
 * so its specs are split by capability — aggregation tiers, tier isolation, purity,
 * executor/settings guards, provider resolution, tracker seam — and every one of them
 * needs the same two things: a valid {@code .gnomish/} tree to mutate one file of, and
 * a one-line way to invoke the loader with a stub registry.
 *
 * <p>Distinct from {@link InvalidFixtureSupport}, which builds the deliberately
 * <em>minimal</em> baseline of the invalid-fixture battery (task 7.2): the trees here
 * are the full, semantically rich ones — two stages, judge criteria file, external
 * check — that the composition-point specs need in order to reach the later tiers.
 * The one thing both need, {@link GnomishTreeWriter#write}, lives in the shared parent.
 *
 * <p>Implements FR1, FR8 of load-pipeline-config.
 */
trait PipelineLoaderFixtureSupport implements GnomishTreeWriter {

    /**
     * Loads the built tree with an empty tracker-validator registry — no tracker type is
     * known, so no adapter import crosses the {@code TrackerPortBoundarySpec} gate.
     */
    LoadOutcome loadTree() {
        PipelineLoader.load(getRoot(), [:], TrackerValidatorStub.discoveredGithubCheckProvider())
    }

    /** Loads the built tree with a registry in which {@code github} is a known tracker type. */
    LoadOutcome loadTreeWithGithubTracker() {
        PipelineLoader.load(
                getRoot(),
                TrackerValidatorStub.acceptingGithub(),
                TrackerValidatorStub.discoveredGithubCheckProvider())
    }

    /** Writes a complete, structurally- and semantically-valid two-stage tree. */
    void writeValidTree() {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write('pipeline.yaml', 'stages:\n  - plan\n  - build\n')
        write('stages/plan/stage.yaml', planManifest())
        write('stages/plan/instructions.md', 'plan it\n')
        write('stages/plan/accept.md', 'criteria\n')
        write('stages/build/stage.yaml', buildManifest())
        write('stages/build/instructions.md', 'build it\n')
    }

    /**
     * Writes {@code configYaml} together with the valid single {@code plan} stage it needs
     * in order to reach the tier under test — used by specs that vary only {@code config.yaml}.
     */
    void writePlanOnlyTree(String configYaml) {
        write('config.yaml', configYaml)
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', planManifest())
        write('stages/plan/instructions.md', 'plan it\n')
        write('stages/plan/accept.md', 'criteria\n')
    }

    /** The {@code plan} manifest: one output, a command check and a judge check, agent-cli executor. */
    String planManifest() {
        '''\
purpose: plan the work
outputs:
  - id: plan-doc
executor:
  type: agent-cli
  model: some-model
instructions: stages/plan/instructions.md
verify:
  - type: command
    command: echo ok
  - type: judge
    criteriaFile: stages/plan/accept.md
    model: judge-model
    votes: 1
advancement: auto
'''
    }

    /** The {@code build} manifest: consumes {@code plan-doc}, builtin + external checks, manual advancement. */
    String buildManifest() {
        '''\
purpose: build the work
inputs:
  - kind: internal
    producerOutputId: plan-doc
  - kind: source
outputs:
  - id: build-artifact
executor:
  type: agent-cli
  model: cli-model
instructions: stages/build/instructions.md
verify:
  - type: builtin
    name: files_exist
  - type: external
    checkId: ci
    interval: 5s
    timeout: 60s
advancement: manual
'''
    }
}
