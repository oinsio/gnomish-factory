# Design: add-sandbox-gha-executor

## Context

Driven by FR1–FR11, NFR-S1/S2, NFR-C1 of the proposal. The explore
sessions fixed the security kit for GHA (workflow from the default
branch via `workflow_dispatch`, minimal permissions with read-only
`GITHUB_TOKEN`, no privileged secrets on gnome-parameterized runs, OIDC
instead of static secrets, ephemeral runners, honest weakest-egress
passport) and the step-0 precedent (integration tests as CI `external`
checks) proved the platform mechanics: dispatch, platform-authored
conclusions, polling. This design shapes those into a port adapter.

## Goals / Non-Goals

Design goals: make the risky things structurally impossible (privileged
secrets, push from the runner, gnome-authored workflow definitions)
rather than policed; make the rest loudly declared passport facts.
Non-goals: self-hosted runners, guard-grade egress, gateway/depot
reachability (proposal NG1–NG3).

## Decisions

### D1. Exec = one dispatched run of a default-branch workflow
`exec(cmd, env)` maps to: dispatch the factory-owned workflow via
`workflow_dispatch` with the command and task-branch ref as inputs;
poll the run; deliver output from run logs and the exit code from the
platform-authored conclusion. `workflow_dispatch` executes the
definition from the **default branch** regardless of which ref the run
checks out — GitHub's own semantics make "the gnome edits its cage"
structurally inert (its branch's workflow files are just files; the
pin-check still flags them). Polling reuses the external-check
discipline from change A: conclusions only from the workflow-run API
(platform-authored), never check-run statuses; factory-side rate
limits. Exec granularity starts as one run per exec call (Q2 revisits
if quota pressure demands batching). Alternative rejected: triggering
on push to gnome branches (`push:` workflows on gnome branches see
repository secrets and execute gnome-influenceable definitions —
exactly threat #26).
<!-- implements FR1, FR2, FR7 of add-sandbox-gha-executor -->

### D2. Fresh-per-exec is a passport fact, not a limitation to hide
Each dispatched run gets a new ephemeral VM; nothing survives between
execs except the branch. The passport therefore declares
fresh-per-exec: reconciliation knows segment reuse is impossible, warm
caches don't exist (workflow cache actions are the runner-native
substitute), and `requires-fresh`/`verify-in: fresh-box` are satisfied
by construction. This is the same honesty mechanism as the host
adapter's "isolation: none" — the family absorbs odd adapters through
passports, not special cases in the engine.
<!-- implements FR3 of add-sandbox-gha-executor -->

### D3. Harvest = bundle artifact + factory-side fast-forward apply
The run's last step creates a git bundle of the task branch and uploads
it as a workflow artifact; the factory downloads the artifact and
applies it to its clone with the factory-fixed refspec,
fast-forward-only, then pushes to the real remote itself. Push monopoly
holds with zero push-capable credentials on the runner — stronger than
scoping a token. Bundle apply is idempotent (re-applying the same tip
is a no-op; a rewritten history is refused by fast-forward semantics),
which makes crash recovery re-download-safe. Alternative rejected:
runner pushes with a scoped App token (a write credential inside the
weakest-egress environment, and push monopoly broken for one adapter —
a structural regression for a convenience gain).
<!-- implements FR5, NFR-R2 of add-sandbox-gha-executor -->

### D4. Credential floor: read-only token, no privileged secrets, OIDC above
The shipped workflow template pins `permissions:` to the minimum
(contents: read; nothing else) — the run can check out and upload
artifacts, full stop. No privileged repository or environment secret is
referenced by the template, and docs require the repository to keep
gnome-reachable workflows secret-free (verifiable checklist item, FR11).
Anything a run legitimately needs beyond that must arrive as a
short-lived OIDC-federated credential, scoped through a **protected
GitHub Environment**: the environment's deployment branch policy admits
only the default branch, so a gnome-parameterized run can never assume
the OIDC subject that mints the credential — protection rules make the
scope structural, not conventional. In v1 that credential set is empty:
check-only stages need no AI key, which is why v1 leans check-only
(Q1). Industry precedent for gating gnome-influenced CI: GitHub itself
puts a human "Approve and run workflows" gate in front of its Copilot
agent's runs; our default-branch-definition + no-secrets floor achieves
the same containment structurally, and the environment's protection
rules are where an operator adds a required-reviewer gate if they want
the human step too. Alternative rejected: GitHub Actions secrets for
factory credentials (static, repo-wide, reachable by every workflow
with secrets access — the Devin-class env-leak pattern).
<!-- implements FR6, NFR-S1 of add-sandbox-gha-executor -->

### D5. Egress: declared weakest, best-effort filtering optional
There is no factory guard in front of a GitHub-hosted runner, and the
adapter never pretends otherwise: the passport declares best-effort
egress only, and stages that declare guard-dependent needs are refused
at reconciliation. Runner-level filtering (harden-runner-class) is
defense-in-depth the operator may opt into; it is not the shipped
default (a third-party action in the trust path is its own
supply-chain decision — Q4). Denied/observed egress from such tooling
feeds the findings funnel when present. The depot is unreachable, so
direct registries go into the passport as a fact (the step-0 precedent
already accepted this for CI checks).
<!-- implements FR8, NFR-S2 of add-sandbox-gha-executor -->

### D6. Run bookkeeping: factory-stamped, crash-safe, budgeted
Every dispatched run carries factory metadata (task id, exec id) in its
inputs/name, so a restarted factory can list its own in-flight runs and
either re-attach (continue polling) or cancel and re-dispatch from
branch state. `dispose()` cancels the environment's in-flight run;
startup cleanup cancels orphaned factory-stamped runs — an orphaned run
is both a compute bill and a stale writer, same cost logic as change
D's namespaces. Per-task run counts and minutes go into the task report
(runner minutes are the adapter's cost currency). Polling, dispatch,
and artifact downloads share one API budget with the tracker port's
existing quota discipline.
<!-- implements FR9, NFR-C1, NFR-R2 of add-sandbox-gha-executor -->

### D7. Provisioning = workflow setup steps; no snapshot extension
GHA-bound stages declare their toolchain in the factory-owned workflow
template via standard setup/cache actions. The change-B snapshot cache
stays container-only: there is no factory-controlled image on GitHub's
runners, and pretending `setup.sh` semantics transfer would blur who
owns what. A repo's `.gnomish/setup.sh` is not executed on runners in
v1; if a GHA-bound stage needs project toolchain steps, they live in
the template the operator installs (from the default branch — law by
construction). Alternative rejected: container jobs running the
factory image on runners — possible, but adds a registry dependency
and start latency for little isolation gain on an already-ephemeral
VM; revisit only if toolchain drift becomes real friction.
<!-- implements FR10 of add-sandbox-gha-executor -->

### D8. Streamed output is polled logs, declared as such
The port promises streamed output; GitHub exposes run logs with delay
and pagination. The adapter delivers incremental output on each poll
tick and the complete log at conclusion, and its passport declares
coarse-grained streaming (Q3 fixes the exact contract wording). This
is a fidelity note, not a correctness issue: every consumer decision
(round outcome, findings) keys on the exit code and final artifacts,
which are exact. <!-- implements FR1 of add-sandbox-gha-executor -->

## Risks / Trade-offs

- [Weakest egress in the family: a compromised run can call anywhere] →
  passport + reconciliation keep guard-dependent stages off GHA;
  check-only v1 keeps AI keys off runners entirely; docs state what a
  compromised run can and cannot reach (UX4).
- [Repository misconfiguration re-introduces secrets] → verifiable
  checklist (FR11) + M4 assertion; the shipped template requests
  nothing beyond contents: read.
- [GitHub API quotas under many parallel tasks] → shared budget with
  tracker-port discipline (D6); exec granularity revisited under
  pressure (Q2).
- [Queue latency makes rounds slow at busy hours] → declared cost
  profile; binding guidance prefers GHA for long-running checks where
  queue time amortizes.
- [Bundle artifacts for huge repos are heavy] → bundles carry only the
  branch delta by prerequisite negotiation where possible; size caps
  from the findings funnel apply to logs, not bundles — document
  limits, revisit on real pain.
- [Third-party hardening action in the template] → not shipped by
  default (D5); operator opt-in with the trade-off documented.

## Migration Plan

1. Adapter skeleton against a mocked GitHub API: dispatch, poll,
   conclusion mapping, bookkeeping (D6); contract suite with
   fresh-per-exec passport semantics (M1).
2. Workflow template + bundle harvest + fast-forward apply; M2/M3 on
   the live profile.
3. Credential floor verification (M4) + checklist docs (FR11).
4. Crash-safety (M5), cost reporting, orphan-run cleanup; final docs
   (UX4 positioning).
   Rollback at any point: unbind the adapter; the step-0 external-check
   pattern is untouched throughout.

## Open Questions

- Q1 (proposal): AI credential delivery for agent rounds — v1 ships
  check-only; revisit with real demand.
- Q2 (proposal): exec granularity under API quota pressure — measure in
  step 1.
- Q3 (proposal): streaming contract wording in the passport — fix in
  step 1 with real log-latency data.
- Q4 (proposal): harden-runner in the template vs docs-only — decide in
  step 3.
- Q5 (proposal): live-E2E test repository setup — decide in step 2.
