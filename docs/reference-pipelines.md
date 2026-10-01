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

| name         | link                                                      | stack                                                                    | stages                                                                                               | maturity | tested on                       | maintainer | license         |
|--------------|-----------------------------------------------------------|--------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|----------|---------------------------------|------------|-----------------|
| `gf-tests`   | [oinsio/gf-tests](https://github.com/oinsio/gf-tests)     | Java 25, Gradle 9, Spock 2; OpenSpec                                     | `specify`, `implement`, `archive`, `deliver`                                                         | `runs`   | built from source, before 0.1.0 | oinsio     | Apache-2.0      |
| `time-zones` | [oinsio/time-zones](https://github.com/oinsio/time-zones) | TypeScript, React, Vite, pnpm; Vitest, playwright-bdd, Stryker; OpenSpec | `specify`, `review-specs`, `fix-specs`, `implement`, `review-code`, `fix-code`, `archive`, `deliver` | `runs`   | built from source, before 0.1.0 | oinsio     | Apache-2.0      |

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

### `time-zones`: TypeScript/React PWA with OpenSpec and review loops

A client-only progressive web app (world clock, time zone conversion, meeting planning),
deployed to GitHub Pages. Unlike `gf-tests`, it is a real product: its features after the
first scaffold were delivered by the factory from GitHub issues (pull requests #13, #21, #28,
#31, #33).

**Pipeline.** Eight stages in `.gnomish/stages/<stage>/`, all on the `agent-cli` executor.
The `gf-tests` shape is extended with a review-and-fix pair after the specification and
after the code: a reviewer stage writes numbered findings to a report and changes nothing
else, and a fixer stage resolves or rejects every finding with evidence. The stages that
review, and every judge that decides between reviewer and fixer, run on Opus; `implement`,
`fix-code`, `archive` and `deliver` run on Sonnet.

| Stage          | Produces                                                           | Command checks before the judge                                                                             |
|----------------|--------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `specify`      | one OpenSpec change under `openspec/changes/`                      | one active change; `openspec validate --changes --strict`; proposal sections and requirement IDs            |
| `review-specs` | `review-specs.md` with `R<n>` findings, all `open`                 | report sections; well-formed findings; no other file touched                                                |
| `fix-specs`    | the change revised, every finding resolved or rejected             | no `open` finding; a resolution for each; nothing outside the change; strict validation                     |
| `implement`    | the code and BDD specs in `packages/client`, every task ticked     | all tasks ticked; 300-line file cap; the CI steps: lint, typecheck, unit tests, build, bundle size, BDD E2E |
| `review-code`  | `review-code.md` in the same format, with scoped mutation runs     | report sections; well-formed findings; no other file touched                                                |
| `fix-code`     | the findings fixed with TDD or rejected with evidence              | no `open` finding; tasks stay ticked; the CI steps again                                                    |
| `archive`      | the change archived under `YYYY/MM/`, spec deltas merged           | no active change; grouped archive layout; `openspec validate --specs --strict`                              |
| `deliver`      | an open pull request against `main`, body mirrored to `pr-body.md` | the pull request targets `main`; real title; body references the issue and equals `pr-body.md` — no judge   |

**Worth copying:**

- Review and fix as separate stages: the reviewer cannot edit what it reviews, so its
  findings are a durable report the fixer, the judge and the human all read.
- Both bindings: `.gnomish/factory/` holds a `project.yaml` template for `host` and one for
  `container`, a sandbox `Dockerfile`, and `build-sandbox`, which builds the image with the
  tool versions the repository pins. The container template's egress allowlist has three
  hosts.
- A wrapper script (`.gnomish/factory/gnomish`) that sets `--dir`, reads its settings from a
  committed env file with a git-ignored local override, and exports the stage tokens from
  the project's secrets folder.
- The README's setup section: a numbered, copyable walk-through from the tools to the first
  `serve`, with a table of every secret, who reads it and which rights it needs.

**Limitations:**

- The `implement` and `fix-code` checks run the BDD E2E suite, so the host binding needs
  Playwright Chromium installed; the container image carries it.
- `deliver` needs `GH_TOKEN` with Contents and Pull requests rights; without it the wrapper
  hands `gh` the tracker token, which then needs those rights too.
- Small codebase (one client package); stage turn budgets are tuned for it.

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
