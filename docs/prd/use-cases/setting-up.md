# Setting up the installation and the project

Group 1 of [`../use-cases.md`](../use-cases.md): the events before the first task, from "the
factory is installed on this machine" to "this project's law and boundary are in place". The
voices are the factory operator, the pipeline author, the security owner and the repository
owner; the stories follow the form and rules of the index. Trying a task without a tracker is
`UC-RUN-ADHOC-TASK` in group 2; the box at run time is group 4.

## Factory operator (`SH-FACTORY-OPERATOR`)

### UC-INSTALL-A-VERIFIED-RELEASE — Install or upgrade from a release I can check
**When** I install the factory or move to a newer release,
**I want** an archive whose sums and provenance I can verify against the published release,
a launcher that refuses the wrong Java by name, and release notes that say what my home and
clones need before the first command,
**so that** an upgrade costs me a read of the notes, never a broken first run.

### UC-SEE-READINESS-IN-ONE-RUN — Learn everything that would stop the factory, at once
**When** I have installed the factory or changed something on the machine,
**I want** one command that lists every problem that would stop the factory on this machine
for this project, with the fix for each,
**so that** I fix all of them in one pass instead of one per failed run.

### UC-REGISTER-A-CLONE — Point the factory at a project once
**When** I bring a project to the factory,
**I want** to register its clone once by name and path, every later command to find it from
the directory I am in, and a refusal that names the line to paste when I am elsewhere,
**so that** nothing about the project is typed twice and nothing runs in a place I did not
register.

### UC-ONE-CONFIGURATION-WITH-ORIGINS — Know where every setting comes from
**When** I set or read a factory setting,
**I want** one file per level (host, project, command line), every key saying where it may be
set, the origin of each effective value shown on request, and a stop before any side effect
that lists every misplaced or unknown key with its fix,
**so that** I never hunt for which file won.

### UC-KEEP-SECRETS-IN-FILES — Hand over a secret without writing it down twice
**When** I give the factory a token or a key,
**I want** it read by name from a permission-checked file under the factory home or from the
environment, through one provider, never written into a configuration file, a log or a report,
**so that** a shared workstation or a pasted configuration leaks nothing.

## Pipeline author (`SH-PIPELINE-AUTHOR`)

### UC-HEAR-EVERY-LAW-ERROR-AT-ONCE — Get the law's errors before anything is paid for
**When** I write or change the law,
**I want** every structural and semantic error reported together, each with its file and
location, before any claim, branch or paid round,
**so that** a configuration slip costs one edit, not one round per slip.

### UC-DRY-RUN-THE-LAW — See the pipeline run before it costs anything
**When** I want to watch a pipeline go through its stages,
**I want** to run it from a scratch directory with no tracker, no branch and a scripted
stand-in for the gnome and the judge,
**so that** I debug stage order, checks and criteria without a token or a model.

### UC-ASK-ONLY-TO-TIGHTEN — Declare needs that can only narrow the boundary
**When** I declare in the law what a stage needs (a fresh box, a destination, a stricter limit),
**I want** the declaration accepted only when it tightens the operator's boundary, and refused
with a located error when it would loosen it,
**so that** a reviewer of the law knows a merged change to it can never widen what the gnome
may do.

## Security owner (`SH-SECURITY-OWNER`)

### UC-SET-THE-BOUNDARY-IN-ONE-FILE — Keep a project's boundary in one place
**When** I set a project's sandbox boundary (the binding, the image, the allowlist, the
variables passed through),
**I want** it read from the project's own file and nowhere else, not the host file, not the
command line, not the repository, with the box as the binding unless I bound the host myself,
**so that** reviewing one file tells me what a project's gnomes may reach.

### UC-TRUST-THE-IMAGE-CONTRACT — Know the image matches the factory
**When** I build or pull the image a project's boxes run on,
**I want** it built on a base the factory publishes and verifies, carrying a contract version
the factory checks before it starts a box,
**so that** a drifted image fails at start, not as a broken round in the night.

Tension with `UC-WORK-IN-OWN-COPY`: the gnome wants the project's full toolchain in the box;
the owner wants the image to stay on the verified base.

### UC-CHOOSE-A-STRONGER-BOUNDARY — Bind a harder boundary where a project needs it
**When** a project's tasks run untrusted builds or need a container runtime of their own,
**I want** to bind a boundary whose passport declares hardware isolation between tasks and
Docker inside, under the same law, guard and allowlist,
**so that** the shared kernel of the container boundary is a choice I make per project, not a
limit I inherit.

## Repository owner (`SH-REPOSITORY-OWNER`)

### UC-ALLOW-THE-BASES — Say where the factory may start from
**When** I accept the law into my repository,
**I want** to state on the default branch which bases a task may start from, read from the
refreshed default branch only, with the default branch discovered rather than assumed,
**so that** neither a task branch nor a fork can widen where the factory starts.

## Coverage

The second column is the dry run of the `**Serves**:` lines: the capability and the
requirement headings that would carry the key. The third names entries of
[`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-INSTALL-A-VERIFIED-RELEASE` | `release/release-distribution`: A distribution archive with a launcher, A pushed version tag publishes a verified release, The factory reports its version; `docs/releases/` | none |
| `UC-SEE-READINESS-IN-ONE-RUN` | `operator/operator-configuration`: Violations stop startup with one complete report; `git/git-transfer`: Git version floor at startup (one refusal per run) | `GP-NO-READINESS-COMMAND` |
| `UC-REGISTER-A-CLONE` | `operator/project-registry`: Projects are registered explicitly by clone path, Every project-scoped command resolves one registered clone, The registry is inspectable | `GP-FACTORY-IN-OPERATOR-CLONE` |
| `UC-ONE-CONFIGURATION-WITH-ORIGINS` | `operator/operator-configuration`: Configuration is assembled from four ordered sources, Every key declares where it may be set, Violations stop startup with one complete report; `operator/project-registry`: One home directory holds all operator state | none |
| `UC-KEEP-SECRETS-IN-FILES` | `operator/secrets-provider`: Env/file adapter is the zero-infrastructure default, All factory secrets resolve through the port, Secrets are fail-closed and never leak outward; `operator/vendor-connection-profile`: Operator config supports named vendor connection profiles | none |
| `UC-HEAR-EVERY-LAW-ERROR-AT-ONCE` | `pipeline/pipeline-config`: Structural validation, Semantic validation, Aggregated, located validation results, Local sanity validation of mechanism and check configs, Definition validated at startup, bound per task from the base | none |
| `UC-DRY-RUN-THE-LAW` | `cli/manual-run`: Run modes, Manifest-driven mechanism with interactive override; `operator-guide-run.md` (the stand-in agent) | `GP-STAND-IN-AGENT-NOT-SHIPPED` |
| `UC-ASK-ONLY-TO-TIGHTEN` | `pipeline/pipeline-config`: Repo declarations can only tighten; `sandbox/execution-environment`: Adapter passports and fail-closed reconciliation; `sandbox/sandbox-egress`: Default-deny allowlist owned by the operator | `GP-LAW-UNSEALED-AGENT-FILES` |
| `UC-SET-THE-BOUNDARY-IN-ONE-FILE` | `operator/operator-configuration`: Every key declares where it may be set; `sandbox/execution-environment`: Container is the default binding; `sandbox/sandbox-egress`: Default-deny allowlist owned by the operator | none |
| `UC-TRUST-THE-IMAGE-CONTRACT` | `sandbox/sandbox-egress`: Image carries the egress plumbing (a recipe copied by hand); `docs/examples/sandbox-image/` | `GP-IMAGE-CONTRACT-UNCHECKED` |
| `UC-CHOOSE-A-STRONGER-BOUNDARY` | `sandbox/execution-environment`: Adapter passports and fail-closed reconciliation (the passport exists, no adapter declares the boundary) | `GP-NO-HARDWARE-BOUNDARY` |
| `UC-ALLOW-THE-BASES` | `git/base-ref-resolution`: Allowed bases govern per-task selection, Base configuration is read from the refreshed default branch only, Default branch is discovered, not assumed | none |
