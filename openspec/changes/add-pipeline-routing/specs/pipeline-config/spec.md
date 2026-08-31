# pipeline-config — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Named pipelines over a shared stage pool
The loader SHALL support multiple named pipelines in one `.gnomish/` tree,
each an explicit linear sequence of stage names drawing from the shared
`stages/` pool; a stage directory MAY be referenced by several pipelines and
is validated within each referencing pipeline's graph. A legacy `.gnomish/`
with the single-pipeline shape SHALL load unchanged as one pipeline with a
defined default name, routed for every task. Each loaded definition SHALL
carry its name and a deterministic content hash covering everything the law
freeze reads for it.
<!-- implements FR1, FR5 of add-pipeline-routing -->

#### Scenario: Two pipelines share a stage
- **WHEN** pipelines `feature` and `bugfix` both list stage `implement`
- **THEN** both load, each validating `implement` inside its own order and
  artifact graph

#### Scenario: Legacy shape loads as the default pipeline
- **WHEN** a `.gnomish/` predating routing (single stage list, no table) is
  loaded
- **THEN** it yields one named pipeline treated as the routing default, and
  behavior matches today's exactly

#### Scenario: Definition hash is stable
- **WHEN** the same `.gnomish/` content is loaded twice
- **THEN** each pipeline's content hash is identical across loads

## MODIFIED Requirements

### Requirement: Artifact references by identifier
Each stage output SHALL declare a stable `id`, unique across each pipeline
that includes the stage. An internal input SHALL reference the `id` of an
output produced by a stage that appears **earlier** in the same pipeline's
order. A `source` input SHALL declare that it has no producing stage. A
stage shared by several pipelines has its references validated per
pipeline.
<!-- implements FR4 of load-pipeline-config -->
<!-- implements FR5 of add-pipeline-routing -->

#### Scenario: Internal input resolves to an earlier output
- **WHEN** a stage input references an output `id` produced by an earlier
  stage of the same pipeline
- **THEN** validation accepts the reference and the model links input to
  producing stage

#### Scenario: Source input needs no producer
- **WHEN** a stage input is declared as `source`
- **THEN** validation accepts it without requiring a producing stage

#### Scenario: Dangling or forward reference is rejected
- **WHEN** an internal input references an `id` that no stage produces, or
  that is produced only by a later stage of that pipeline
- **THEN** validation reports a located error identifying the input and the
  missing/late producer

#### Scenario: Duplicate output id is rejected
- **WHEN** two stages of one pipeline (or one stage twice) declare outputs
  with the same `id`
- **THEN** validation reports a located error naming the duplicated `id`
  and both declaring stages

#### Scenario: Same id in disjoint pipelines is legal
- **WHEN** pipelines `feature` and `spike` (sharing no stage) each contain
  an output id `report`
- **THEN** validation accepts both

### Requirement: Semantic validation
The loader SHALL enforce cross-file rules: unique stage names; every stage
named by any pipeline has a matching `stages/<name>/stage.yaml`; every stage
directory is referenced by **at least one pipeline** (no dangling
definitions); every internal input is satisfied by an earlier stage's output
within each referencing pipeline; every referenced file (`instructions.md`,
`judge` acceptance-criteria files) exists; and the routing table's entries
and default name existing pipelines.
<!-- implements FR6 of load-pipeline-config -->
<!-- implements FR1, FR5 of add-pipeline-routing -->

#### Scenario: Pipeline stage without a manifest is rejected
- **WHEN** any pipeline names a stage that has no `stages/<name>/stage.yaml`
- **THEN** validation reports a located error

#### Scenario: Dangling stage directory is rejected
- **WHEN** a `stages/<name>/` directory exists but no pipeline references it
- **THEN** validation reports a located error naming the unreferenced stage

#### Scenario: Missing referenced file is rejected
- **WHEN** a stage's `instructions.md` or a `judge` check's
  acceptance-criteria file does not exist
- **THEN** validation reports a located error naming the missing file

#### Scenario: Stage shared by two pipelines is not dangling
- **WHEN** a stage directory is referenced by one of three pipelines
- **THEN** the dangling check passes for it
