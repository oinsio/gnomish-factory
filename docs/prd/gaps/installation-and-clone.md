# Gaps: installation and clone

Entries of [`../gaps.md`](../gaps.md) about the factory on the machine: readiness, the
registered clone, the slots that share it. The form and the vocabulary are in the index.

### GP-NO-READINESS-COMMAND — Readiness problems surface one per failed run
**Breaks:** `UC-SEE-READINESS-IN-ONE-RUN`
**Where today:** `operator/operator-configuration`: Violations stop startup with one complete report (configuration only); every other check (git floor, Docker, image, agent credential, tracker token) refuses at the first command that needs it
**Kind:** `missing`
**Resolution:** `planned: add-doctor-command`

### GP-FACTORY-IN-OPERATOR-CLONE — The factory works inside the operator's own clone
**Breaks:** `UC-REGISTER-A-CLONE`, `UC-REBUILD-FROM-ORIGIN`
**Where today:** `operator/project-registry`: Projects are registered explicitly by clone path; `git/git-task-persistence`: Factory git executes no untrusted content (writes the clone's hook path); the operator must create the clone on the ref backend the factory requires, by hand after a loss as at the start, and the operator's hooks, configuration and IDE fetches reach the factory's git
**Kind:** `wrong-turn` (decision: D7 of `add-git-workflow`, the factory runs in the registered clone; premise: the factory's clone is the operator's clone, dating from the manual-run era and never decided; refuted by `UC-SHARE-A-PROJECT-ACROSS-MACHINES`, `UC-RUN-SLOTS-UNATTENDED` and the IDE fetch of `UC-REGISTER-A-CLONE`; the 2026-10-21 review traced eight defects to it)
**Resolution:** `open` (a factory-owned clone from the origin URL is the candidate; no change written)

### GP-SLOTS-RACE-ON-CLONE-CONFIG — Two slots claiming at once can fail on the clone's configuration lock
**Breaks:** `UC-RUN-SLOTS-UNATTENDED`
**Where today:** `serve/factory-serve`: Concurrent slots share one clone safely (promised; the hardening step writes the shared clone's configuration from every slot under no lock, and the loser's fresh claim fails)
**Kind:** `wrong-turn` (decision: the factory hardens the clone by writing its configuration file; premise: one writer at a time; refuted by `UC-RUN-SLOTS-UNATTENDED`; see `GP-FACTORY-IN-OPERATOR-CLONE`)
**Resolution:** `planned: own-git-invocation-policy` (the factory never writes the clone's configuration; every setting travels on the command line)

### GP-STAND-IN-AGENT-NOT-SHIPPED — A dry run without a model needs the factory's source tree
**Breaks:** `UC-DRY-RUN-THE-LAW`
**Where today:** `cli/manual-run`: Run modes (in-place, no tracker); the scripted stand-in for the gnome and the judge lives in the factory's test fixtures and is absent from the distribution archive
**Kind:** `missing`
**Resolution:** `open`
