# Developing the factory

Group 10 of [`../use-cases.md`](../use-cases.md): the events of the factory's own making: a
change is built and checked, a release is tagged, a plugin is written against the published
contract, a contributor arrives, and the factory works on its own repository. The voices are
the factory maintainer, the external contributor and the extension author; the stories follow
the form and rules of the index. Installing a release is `UC-INSTALL-A-VERIFIED-RELEASE`
(group 1); running a pipeline without a model is `UC-DRY-RUN-THE-LAW` (group 1).

## Factory maintainer (`SH-FACTORY-MAINTAINER`)

### UC-ASK-THE-BUILD-IF-MY-CHANGE-IS-OK — Get one answer from one command
**When** I have changed the factory,
**I want** one command that compiles, runs every spec, reports coverage and mutates only the
classes my branch changed, saying which scope it chose and why,
**so that** "is my change OK" costs one run and no reading of the build's internals.

### UC-FAIL-AT-COMPILE-TIME-NOT-IN-REVIEW — Have the rules enforced by the build
**When** I break a rule of the project: a null the code cannot prove, dead code, a file past
the cap, a dependency in the wrong direction, a decorator that forgets a method,
**I want** the compilation or the build to fail naming the place and the rule,
**so that** review reads the design, never the checklist.

### UC-LINK-EVERY-SPEC-TO-A-TEST-AND-A-NEED — Keep the pyramid joined
**When** I add or change a requirement, a scenario or a test,
**I want** the build to refuse a requirement that names no change, a scenario that no test
exercises, a marker that names no scenario, and a requirement that serves no need,
**so that** a mechanism without a need and a promise without a test never land unnoticed.

### UC-BUILD-THE-SAME-ANYWHERE — Build on a clean machine as on CI
**When** I build on a fresh machine, on Linux CI, or twice on the same commit,
**I want** the same pinned toolchain and dependencies, a refused artifact when one is
tampered, and byte-identical archives from the same commit,
**so that** "works on my machine" is never an answer.

### UC-HEAR-WHEN-MAIN-ROTS — Learn of a whole-tree failure without running it
**When** the whole-tree gate or a security scan fails on the main branch,
**I want** a scheduled run to open an issue, or comment on the one still open, naming the
failing modules and linking the run,
**so that** a gate no branch runs is still a gate someone reads.

### UC-CUT-A-RELEASE-BY-A-TAG — Release by pushing one tag
**When** I push a version tag,
**I want** the release published only after every check of that commit passed, with the
archives, their sums, a software bill of materials, provenance attestations, the license,
and the release notes written for that version, the version read from the tag alone,
**so that** a release is one push and nothing is assembled by hand.

### UC-LET-THE-FACTORY-WORK-ON-ITSELF — Hand the factory its own changes
**When** I file a change of the factory as a task on this repository,
**I want** the project's own pipeline to clear its dependencies, review and fix its
artifacts, implement it, review and fix the code, archive the change and open the pull
request, while the gnome never rebuilds the factory that runs it,
**so that** the factory is its own first project and its law is proven on its own work.

## External contributor (`SH-CONTRIBUTOR`)

### UC-RUN-THE-FULL-GATE-FROM-A-FORK — Have my pull request checked like a maintainer's
**When** I open a pull request from a fork,
**I want** the whole gate to run on it with no secret of the project,
**so that** my change is judged by the same build, and a secret is never within my reach.

### UC-KNOW-THE-WAY-IN — Learn in one page what I can contribute
**When** I want to contribute,
**I want** one page saying what is accepted today, a contributor agreement signed in one
step when pull requests open, and a registry where a plugin of mine is listed,
**so that** I never write a pull request that is closed on arrival.

## Extension author (`SH-EXTENSION-AUTHOR`)

### UC-COMPILE-AGAINST-THE-CONTRACT-ALONE — Build a plugin from the published contract
**When** I write a tracker adapter, a check provider, a sandbox backend or an executor,
**I want** one versioned contract artifact to compile against, a load path identical to the
built-in plugins', and my provider named at startup,
**so that** a plugin needs no fork of the factory and no knowledge of its internals.

### UC-PROVE-CONFORMANCE-WITHOUT-THE-SOURCE-TREE — Run the contract suite against my adapter
**When** my adapter is written,
**I want** the port's contract suite, the same one every shipped adapter passes, runnable
from my own build,
**so that** conformance is a green run, never a reading of the guide.

## Coverage

The second column is the dry run of the `**Serves**:` lines: the capability and the
requirement headings that would carry the key. The third names entries of
[`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-ASK-THE-BUILD-IF-MY-CHANGE-IS-OK` | `build-system/quality-gates`: Single verification command, Mutation testing gate, Branch-scoped mutation target, Mutation scope is announced | none |
| `UC-FAIL-AT-COMPILE-TIME-NOT-IN-REVIEW` | `build-system/quality-gates`: Static analysis gate, Code format gate, Production source files stay within the size cap, Delegating decorator completeness gate; `build-system/module-layering`: Enforced acyclic dependency direction | none |
| `UC-LINK-EVERY-SPEC-TO-A-TEST-AND-A-NEED` | `build-system/quality-gates`: Spec traceability line, Scenario-to-test link, One spec-contract checker, two launchers | `GP-SPECS-NOT-LINKED-TO-NEEDS` |
| `UC-BUILD-THE-SAME-ANYWHERE` | `build-system/quality-gates`: Reproducible build, Continuous integration; `build-system/dependency-verification`: Every resolved artifact is pinned, Tampered artifacts are refused with an actionable error | none |
| `UC-HEAR-WHEN-MAIN-ROTS` | `build-system/quality-gates`: Scheduled whole-tree mutation run, Security scanning | none |
| `UC-CUT-A-RELEASE-BY-A-TAG` | `release/release-distribution`: The product version comes from the release tag, A distribution archive with a launcher, A pushed version tag publishes a verified release; `release/project-licensing`: Every distributed artifact carries the license and notice | none |
| `UC-LET-THE-FACTORY-WORK-ON-ITSELF` | `pipeline/pipeline-config`: Pipeline law binds per invocation; `reference-pipelines.md` and the law under `.gnomish/` (the jar is a copy the gnome never rebuilds) | `GP-FACTORY-ON-ITSELF-HOST-ONLY` |
| `UC-RUN-THE-FULL-GATE-FROM-A-FORK` | `build-system/quality-gates`: Continuous integration, Security scanning | none |
| `UC-KNOW-THE-WAY-IN` | `release/project-licensing`: Contributing guide states that pull requests are closed, External contributions require a signed contributor agreement, Community plugin registry | none |
| `UC-COMPILE-AGAINST-THE-CONTRACT-ALONE` | `plugin/plugin-api-contract`: Thin plugin-api contract module, Independent semantic versioning of the api, Sample plugin proves the check authoring path; `plugin/github-plugin`: The built-in load path is identical to the third-party load path; `plugin/plugin-discovery`: Discovered providers are reported at startup | `GP-ONLY-TRACKER-AND-CHECK-PORTS-PLUGGABLE` |
| `UC-PROVE-CONFORMANCE-WITHOUT-THE-SOURCE-TREE` | `tracker/tracker-port`: Contract suite covers lease maintenance; `adapter-author-guide.md` (the contract suite is law) | `GP-CONTRACT-SUITE-NOT-PUBLISHED` |
