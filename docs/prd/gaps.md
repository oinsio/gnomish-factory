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
| `wrong-turn` | built, but on a premise the review refuted; closing it means redesign, not a patch |

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
**Breaks:** `UC-KEEP-SECRETS-ON-THE-HOST`, `UC-ONE-EXIT-FROM-THE-BOX`, `UC-GNOME-CANNOT-FORGE-RECORDS`
**Where today:** `sandbox/execution-environment`: Host adapter mechanics; `git/git-task-persistence`: Gnome commits within a round (the host round close reads the gnome's working tree); no guard and no denials in host mode
**Kind:** `one-medium-only`
**Resolution:** `decided` (2026-10-21): host mode stays for work a box cannot serve, such as attached peripherals, special settings or applications, or a specific OS; process, code and security stay as close to container mode as they can, with no guarantee where none can be given; lands in `concept.md` (execution modes), the operator guide, and a `QR-*` whose measure is that the list of differences is kept and complete

### GP-LAW-UNSEALED-AGENT-FILES — The agent CLI's own instruction and policy files are not law
**Breaks:** `UC-SEAL-THE-LAW`
**Where today:** `pipeline/pipeline-config`: Pipeline law binds per invocation (names `.gnomish/` only); `CLAUDE.md`, `.claude/settings.json` and their equivalents are read from the gnome-writable copy
**Kind:** `missing`
**Resolution:** `planned: harden-verification-integrity` (repo-local agent policy cannot widen the gnome; instruction files only where the law declares them protected)

### GP-VERIFY-RUNS-GNOME-TREE — A command check runs the project's commands from the gnome's tree
**Breaks:** `UC-SEAL-THE-LAW`
**Where today:** `pipeline/stage-engine`: Ordered fail-fast verification; the check is law, what it invokes is gnome-editable
**Kind:** `wrong-turn`
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
