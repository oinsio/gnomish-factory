# Gaps: sandbox and secrets

Entries of [`../gaps.md`](../gaps.md) about the box, its boundary, what the gnome may reach
and hold, and host mode. The form and the vocabulary are in the index.

### GP-HOST-MODE-BEST-EFFORT — Host mode gives weaker guarantees than the box
**Breaks:** `UC-KEEP-SECRETS-ON-THE-HOST`, `UC-ONE-EXIT-FROM-THE-BOX`, `UC-GNOME-CANNOT-FORGE-RECORDS`, `UC-JUDGE-ON-CLEAN-GROUND`, `UC-RECOVERY-TRUSTS-ONLY-THE-RECORD`
**Where today:** `sandbox/execution-environment`: Host adapter mechanics; `git/git-task-persistence`: Gnome commits within a round (the host round close reads the gnome's working tree), Factory register ref (in host mode the gnome shares the clone and can move the register, so it is a detection, not a fence); `executor/agent-executor`: CLI judge vote (a host-mode vote runs in the stage workspace the round left behind); no guard and no denials in host mode
**Kind:** `one-medium-only`
**Resolution:** `decided` (2026-10-21): host mode stays for work a box cannot serve, such as attached peripherals, special settings or applications, or a specific OS; process, code and security stay as close to container mode as they can, with no guarantee where none can be given; lands in `concept.md` (execution modes), the operator guide, and a `QR-*` whose measure is that the list of differences is kept and complete

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

### GP-NO-ARTIFACT-DEPOT — A package source is reachable only by opening the allowlist
**Breaks:** `UC-WORK-IN-OWN-COPY`
**Where today:** `sandbox/sandbox-egress`: Default-deny allowlist owned by the operator
**Kind:** `missing`
**Resolution:** `planned: add-artifact-depot`

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

### GP-RUNTIME-OUTAGE-PARKS — A container runtime outage parks the task for a human
**Breaks:** `UC-RIDE-OUT-AN-OUTAGE`
**Where today:** `sandbox/execution-environment`: Runtime outage is an infrastructure failure (the task escalates at once as cannot-execute, an infrastructure park a human must return); `serve/factory-serve`: Remote outage gate holds the feed off the tracker (an origin outage releases the claim and closes on a probe with no human action; the runtime has no gate of its own)
**Kind:** `missing`
**Resolution:** `open`

### GP-NO-EGRESS-ACCESS-LOG — Only denied requests are recorded, never the allowed ones
**Breaks:** `UC-SEE-WHAT-LEFT-THE-BOX`
**Where today:** `sandbox/sandbox-egress`: Denials are captured as structured findings (host, path, method of a denial, carried to the task report); a request the allowlist admits leaves no record in the guard, the report or the branch, so what left the box is known only by what was refused
**Kind:** `missing`
**Resolution:** `open`
