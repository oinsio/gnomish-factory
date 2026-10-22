# Known gaps

Where the factory as built does not meet a story or a quality requirement of this directory.
The register is Volere's "waiting room": one entry per gap, written once here and referenced
by key from the coverage tables of the use-case groups, so a gap that breaks four stories is
one entry, not four cells. An entry is deleted when it closes; git history keeps it.

## The entry form

```markdown
### GP-KEY — one line naming the gap
**Breaks:** the `UC-*` and `QR-*` keys the gap leaves unmet
**Where today:** the capability and requirement that carry the story as far as it goes, or "nothing"
**Kind:** one of the four kinds below
**Resolution:** one of the four resolutions below
```

**Kind** says what sort of work closes the gap:

| Kind | Meaning |
|---|---|
| `missing` | the want was never built; nothing contradicts it, nothing serves it |
| `contradiction` | a requirement promises one thing and the story wants another; one of the two must change |
| `one-medium-only` | served on one execution medium and not on the other; the story holds in the box and fails in host mode, or the reverse |
| `wrong-turn` | built, but on a premise a story refutes; closing it means a decision review, not a patch. The entry names the decision, the premise and the refuting story (`plan.md`, section G) |

**Resolution** says who closes it and when the entry leaves:

| Resolution | Meaning | The entry leaves when |
|---|---|---|
| `open` | nobody has decided yet | a decision moves it to another value |
| `planned: <change>` | an unarchived change under `openspec/changes/` closes it | that change archives |
| `decided: <stance>, lands in <file>` | the gap stays by decision; the stance is a non-goal or a weaker quality requirement | the stance is written in the named file |
| `rejected: <reason>` | the story asked for the wrong thing; the story is rewritten | the story changes |

A `planned:` change that is archived, or a `decided:` stance that has landed, leaves a stale
entry; the gate, once written, reports both.

## Entries

### GP-HOST-MODE-BEST-EFFORT — Host mode gives weaker guarantees than the box
**Breaks:** `UC-KEEP-SECRETS-ON-THE-HOST`, `UC-ONE-EXIT-FROM-THE-BOX`, `UC-GNOME-CANNOT-FORGE-RECORDS`, `UC-JUDGE-ON-CLEAN-GROUND`
**Where today:** `sandbox/execution-environment`: Host adapter mechanics; `git/git-task-persistence`: Gnome commits within a round (the host round close reads the gnome's working tree); `executor/agent-executor`: CLI judge vote (a host-mode vote runs in the stage workspace the round left behind); no guard and no denials in host mode
**Kind:** `one-medium-only`
**Resolution:** `decided` (2026-10-21): host mode stays for work a box cannot serve, such as attached peripherals, special settings or applications, or a specific OS; process, code and security stay as close to container mode as they can, with no guarantee where none can be given; lands in `concept.md` (execution modes), the operator guide, and a `QR-*` whose measure is that the list of differences is kept and complete

### GP-LAW-UNSEALED-AGENT-FILES — The agent CLI's own instruction and policy files are not law
**Breaks:** `UC-SEAL-THE-LAW`, `UC-ASK-ONLY-TO-TIGHTEN` (the agent's repo-local policy file can widen its tool permissions, where the law can only tighten)
**Where today:** `pipeline/pipeline-config`: Pipeline law binds per invocation (names `.gnomish/` only); `CLAUDE.md`, `.claude/settings.json` and their equivalents are read from the gnome-writable copy
**Kind:** `missing`
**Resolution:** `planned: harden-verification-integrity` (repo-local agent policy cannot widen the gnome; instruction files only where the law declares them protected)

### GP-VERIFY-RUNS-GNOME-TREE — A check runs the project's commands from the gnome's tree
**Breaks:** `UC-SEAL-THE-LAW`, `UC-PROTECT-THE-EVIDENCE`, `UC-PIN-THE-EXTERNAL-CHECK`
**Where today:** `pipeline/stage-engine`: Ordered fail-fast verification; the check is law, what it invokes is gnome-editable; `verification/verification-hardening`: Pin-check guards external checks (the pin covers the workflow file, not the build scripts and tests the workflow runs from the attempt commit); no check fails an attempt for touching a test, a threshold or a build script
**Kind:** `wrong-turn` (decision: a command check runs in the attempt's working tree, and an external check runs on it; premise: what the check invokes is the project's code; refuted by `UC-SEAL-THE-LAW`, whose **whatever the gnome writes in its copy** covers the build scripts the check runs)
**Resolution:** `planned: harden-verification-integrity` (sealed assets, protected paths, diff-scope verdict)

### GP-VENDOR-KEY-IN-BOX — The gnome's process holds the model vendor's real key
**Breaks:** `UC-KEEP-SECRETS-ON-THE-HOST`
**Where today:** `sandbox/execution-environment`: Layered positive environment allowlist (layer 3, the AI base-url/auth-token seam)
**Kind:** `contradiction`
**Resolution:** `planned: add-sandbox-hardening` (`sandbox/ai-gateway`: real keys live only at the gateway, virtual keys in the box)

### GP-NO-SPEND-BOUND — Nothing bounds what a round or a task may spend
**Breaks:** `UC-BOUND-A-ROUND`
**Where today:** `executor/agent-executor`: Round timeout and control-file preflight (time only)
**Kind:** `missing`
**Resolution:** `planned: add-sandbox-hardening` for the per-segment budget at the gateway; the per-task bound has no change and stays `open`

### GP-NO-PRECLAIM-TEXT-CHECK — An ambiguous task text costs a round before anyone asks
**Breaks:** `UC-FILE-TASK`
**Where today:** `tracker/tracker-take`: Snapshot at first claim; the project's conventions are enforced only by the gnome's round
**Kind:** `missing`
**Resolution:** `open`

### GP-READY-APPLIER-UNRECORDED — The factory never reads who applied the ready label
**Breaks:** `UC-KEEP-READY-GRANT-TRUSTED`
**Where today:** nothing; `operator-guide-autonomy-gate.md` is guidance only
**Kind:** `missing`
**Resolution:** `open`

### GP-NO-TASK-TYPE — A task cannot choose its pipeline
**Breaks:** `UC-STEER-TASK`
**Where today:** `git/base-ref-resolution` (the base only); `tracker/tracker-port` (designators)
**Kind:** `missing`
**Resolution:** `planned: add-pipeline-routing`

### GP-NO-ARTIFACT-DEPOT — A package source is reachable only by opening the allowlist
**Breaks:** `UC-WORK-IN-OWN-COPY`
**Where today:** `sandbox/sandbox-egress`: Default-deny allowlist owned by the operator
**Kind:** `missing`
**Resolution:** `planned: add-artifact-depot`

### GP-NO-READINESS-COMMAND — Readiness problems surface one per failed run
**Breaks:** `UC-SEE-READINESS-IN-ONE-RUN`
**Where today:** `operator/operator-configuration`: Violations stop startup with one complete report (configuration only); every other check (git floor, Docker, image, agent credential, tracker token) refuses at the first command that needs it
**Kind:** `missing`
**Resolution:** `planned: add-doctor-command`

### GP-FACTORY-IN-OPERATOR-CLONE — The factory works inside the operator's own clone
**Breaks:** `UC-REGISTER-A-CLONE`
**Where today:** `operator/project-registry`: Projects are registered explicitly by clone path; `git/git-task-persistence`: Factory git executes no untrusted content (writes the clone's hook path); the operator must create the clone on the ref backend the factory requires, and the operator's hooks, configuration and IDE fetches reach the factory's git
**Kind:** `wrong-turn` (decision: D7 of `add-git-workflow`, the factory runs in the registered clone; premise: the factory's clone is the operator's clone, dating from the manual-run era and never decided; refuted by `UC-SHARE-A-PROJECT-ACROSS-MACHINES`, `UC-RUN-SLOTS-UNATTENDED` and the IDE fetch of `UC-REGISTER-A-CLONE`; the 2026-10-21 review traced eight defects to it)
**Resolution:** `open` (a factory-owned clone from the origin URL is the candidate; no change written)

### GP-STAND-IN-AGENT-NOT-SHIPPED — A dry run without a model needs the factory's source tree
**Breaks:** `UC-DRY-RUN-THE-LAW`
**Where today:** `cli/manual-run`: Run modes (in-place, no tracker); the scripted stand-in for the gnome and the judge lives in the factory's test fixtures and is absent from the distribution archive
**Kind:** `missing`
**Resolution:** `open`

### GP-IMAGE-CONTRACT-UNCHECKED — Nothing tells an operator their sandbox image has drifted
**Breaks:** `UC-TRUST-THE-IMAGE-CONTRACT`
**Where today:** `sandbox/sandbox-egress`: Image carries the egress plumbing (a recipe the operator copies by hand; the user, the control surfaces and the guard plumbing are re-typed per installation)
**Kind:** `missing`
**Resolution:** `planned: add-sandbox-base-image` (published base, contract label checked before a box starts)

### GP-NO-HARDWARE-BOUNDARY — Every box shares the host kernel, and Docker inside is unsupported
**Breaks:** `UC-CHOOSE-A-STRONGER-BOUNDARY`
**Where today:** `sandbox/execution-environment`: Adapter passports and fail-closed reconciliation (the passport can state the boundary; the container adapter declares a software one, and no adapter declares a hardware one)
**Kind:** `missing`
**Resolution:** `planned: add-sandbox-colima-vm` (a per-task VM adapter of the same port; the cloud and CI executors in `add-sandbox-cloud-executor` and `add-sandbox-gha-executor` are other answers to the same want)

### GP-NO-IMMEDIATE-RETURN — A task given back on purpose waits out the lease
**Breaks:** `UC-STOP-AND-RESTART-WITHOUT-DEBT`
**Where today:** `serve/factory-serve`: SIGTERM stops cleanly within grace promises "an instant return to Ready with no TTL wait", while the port's release verb drops the claim and moves nothing (a documented no-op on the GitHub adapter, pinned by its release contract); the task returns only when a reaper finds the claim stale
**Kind:** `contradiction` (the main spec's scenario and the port's release contract promise opposite things; the scenario's test is a pending feature naming finding F97 and the planned change)
**Resolution:** `planned: add-claim-return` (a fenced return verb for the deliberate callers: shutdown, pre-work outage, usage error)

### GP-SLOTS-RACE-ON-CLONE-CONFIG — Two slots claiming at once can fail on the clone's configuration lock
**Breaks:** `UC-RUN-SLOTS-UNATTENDED`
**Where today:** `serve/factory-serve`: Concurrent slots share one clone safely (promised; the hardening step writes the shared clone's configuration from every slot under no lock, and the loser's fresh claim fails)
**Kind:** `wrong-turn` (decision: the factory hardens the clone by writing its configuration file; premise: one writer at a time; refuted by `UC-RUN-SLOTS-UNATTENDED`; see `GP-FACTORY-IN-OPERATOR-CLONE`)
**Resolution:** `planned: own-git-invocation-policy` (the factory never writes the clone's configuration; every setting travels on the command line)

### GP-EVERY-FINDING-BLOCKS — A check cannot report without failing the stage
**Breaks:** `UC-REPORT-WITHOUT-FAILING`
**Where today:** `pipeline/stage-engine`: Ordered fail-fast verification (the first non-pass verdict ends the attempt; a check carries no severity)
**Kind:** `missing`
**Resolution:** `planned: harden-verification-integrity` (`severity: blocking | advisory` on a check; advisory findings reach the tracker and the attempt record and burn nothing)

### GP-CHECK-TARGET-UNCHECKED — A check's target is found missing only after a paid round
**Breaks:** `UC-HEAR-EVERY-LAW-ERROR-AT-ONCE`
**Where today:** `pipeline/pipeline-config`: Local sanity validation of mechanism and check configs (the loader validates no target liveness by decision: a workflow name, a command binary, a model name); `verification/github-external-check`: Misconfiguration fails fast without burning an attempt (found at the first poll, after the stage's rounds were paid for)
**Kind:** `missing`
**Resolution:** `open` (`add-doctor-command` checks the secrets and the binaries the checks declare, not whether a named workflow or model exists)
