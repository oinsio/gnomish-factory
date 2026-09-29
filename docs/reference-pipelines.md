# Reference pipelines

A **reference pipeline** is a public repository whose `.gnomish/` tree is a working pipeline
for a given technology stack: the pipeline file, the stage manifests, the instructions, and
the judge's acceptance criteria. The factory is an engine and ships no pipeline of its own;
the quality of a gnome's work depends on these files. A reference pipeline gives you a
starting point that has already been run, so you do not start from a blank `.gnomish/`.

> **Listed as-is.** The listed repositories are not part of Gnomish Factory and are not
> covered by its license. Each one is distributed under the license its own repository
> states. A missing license means you have no right to copy it. Read a pipeline's stages
> before you run them: a stage's instructions and command checks run with the permissions
> your binding gives the gnome.

## Maturity levels

Each entry has one level. The level is a claim you can check, not a rating.

| Level      | What the entry has shown                                                                                                                 |
|------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `sketch`   | The pipeline loads (`gnomish run` gets past pipeline validation), but no task has gone through every stage yet.                          |
| `runs`     | At least one real task went through every stage to a delivered outcome on the factory build named in the entry.                          |
| `hardened` | Used regularly on real tasks; every stage has deterministic `command` checks ahead of its `judge`; known failure modes are written down. |

A level applies to the factory build named in the entry. Pipeline manifests follow the
factory's schema, so an entry tested on an older build may need edits on a newer one.

## Pipelines

| name       | link                                                  | stack                                | stages                                       | maturity | tested on                       | maintainer | license          |
|------------|-------------------------------------------------------|--------------------------------------|----------------------------------------------|----------|---------------------------------|------------|------------------|
| `gf-tests` | [oinsio/gf-tests](https://github.com/oinsio/gf-tests) | Java 25, Gradle 9, Spock 2; OpenSpec | `specify`, `implement`, `archive`, `deliver` | `runs`   | built from source, before 0.1.0 | oinsio     | none stated yet  |

## Entries

### `gf-tests`: Java/Gradle/Spock with OpenSpec

A small command-line application (a greeting printer with a saved history) that exists only
so the factory has something real to work on. The application does not matter; the pipeline
and the scripts around it do.

**Pipeline.** Four stages, each with its manifest, instructions and acceptance criteria in
`.gnomish/stages/<stage>/`. Every stage uses the `agent-cli` executor and ends with a `judge`
check after cheaper `command` checks.

| Stage       | Produces                                                           | Command checks before the judge                                                                                                                |
|-------------|--------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| `specify`   | one OpenSpec change under `openspec/changes/`                      | a change directory exists; `openspec validate --changes --strict`                                                                              |
| `implement` | the code and Spock specs the change calls for                      | `./gradlew test`; no unticked item left in the change's `tasks.md`                                                                             |
| `archive`   | the change archived, its spec deltas merged into `openspec/specs/` | no active change left; `openspec validate` on specs and archive; `./gradlew test`                                                              |
| `deliver`   | an open pull request against `main`, body mirrored to `pr-body.md` | the pull request exists and targets `main`; title and body are substantive; published body equals `pr-body.md`; body references the task issue |

**Worth copying:**

- The spec-driven shape: a stage writes the specification, the next implements it, the
  next folds it into the living specs, so the reviewer gets code together with its
  requirements.
- The `deliver` stage: the pipeline itself opens the pull request, updates an existing one
  on retry instead of opening a second, and references the issue with `Refs #N`, so closing
  the issue stays with a human.
- Command checks that print the fix in their failure message (the exact `openspec archive`
  or `gh pr edit` command). The gnome's next attempt receives that message as feedback.
- `gnomish-up`: starts `serve`, the dashboard and a log follower with one command.

**Limitations:**

- Runs with `binding=host`: no sandbox image is built for this project yet.
- The `deliver` stage needs a separate `GH_TOKEN` passed into the stage's environment.
- Hello-world scale: it shows the pipeline works, not how it behaves on a large codebase.

## Add a pipeline

Open an issue from the
[reference-pipeline form](https://github.com/oinsio/gnomish-factory/issues/new?template=reference-pipeline.yml)
and fill in its fields. The maintainer adds the row and the entry from the issue as they
stand; you need no pull request and no git operation. The form's input fields are the
table's columns, one to one; its two text areas give the evidence for the maturity level
and the description that becomes the entry.

The repository you list should have:

- a README that says which stack the pipeline targets and how to run it against a factory;
- a license, so others may copy the files;
- no secrets: tokens are read from files outside the clone or from the environment, never
  committed.

When a listed pipeline is updated for a newer factory build or reaches a higher level, open
the same form again with the entry's current name and what changed.
