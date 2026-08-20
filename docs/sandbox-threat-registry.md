# Sandbox Threat Registry

Consolidated threat model of the gnome sandbox, distilled from the sandbox
explore sessions. Sandbox-related OpenSpec changes reference these threats by
number (`threat #21`); the numbering is stable and must never be reused. A new
threat takes the next free number and is filed under the section that fits it
topically, so numbers inside a section are not contiguous. Terms (**factory**,
**gnome**, **box**, **guard**, ...) and abbreviations are defined in the
project [glossary](glossary.md).

## Contents

- [Legend](#legend)
- [Files and disk access](#files-and-disk-access)
- [Secrets](#secrets)
- [Network](#network)
- [Resources and isolation](#resources-and-isolation)
- [Reward hacking and rules integrity](#reward-hacking-and-rules-integrity)
- [Docker and nested execution](#docker-and-nested-execution)
- [Cloud and k8s](#cloud-and-k8s)
- [Tool trust](#tool-trust)
- [Verdicts, findings and data sinks](#verdicts-findings-and-data-sinks)
- [Cross-cutting principles](#cross-cutting-principles)

## Legend

**Closed by** names the mechanism; **Where** names the change that owns it;
**Status** tells whether that change is implemented or still a proposal:

| Label     | Change                                                                                      | Status                                                                              |
|-----------|---------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| core      | `add-sandbox-core` (change A)                                                               | shipped (archived)                                                                  |
| hardening | `add-sandbox-hardening` (change B: TLS interception, AI gateway, virtual keys, setup phase) | proposal                                                                            |
| colima-vm | `add-sandbox-colima-vm` (local per-task VM)                                                 | proposal                                                                            |
| cloud     | `add-sandbox-cloud-executor` (k8s executor)                                                 | proposal                                                                            |
| gha       | `add-sandbox-gha-executor` (Docker-strategy ladder step 0)                                  | proposal; its predecessor `add-external-check-github-actions` is shipped (archived) |
| depot     | `add-artifact-depot` (package/artifact proxy)                                               | proposal                                                                            |
| shipped   | earlier archived changes                                                                    | shipped (archived)                                                                  |

## Files and disk access

| #  | Threat                                                                                                                                                                   | Closed by                                                                                                                                                                          | Where |
|----|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------|
| 1  | The gnome sees everything we can access (personal files, other worktrees, factory config)                                                                                | container: the box sees only a copy of the project                                                                                                                                 | core  |
| 2  | Deception by text (prompt injection) → reading and leaking secrets                                                                                                       | the whole construction (nothing to leak inside the box + allowlisted egress); accepted as a given                                                                                  | core  |
| 3  | Foreign code execution during build/tests (poisoned library, the Nx "s1ngularity" incident)                                                                              | container + resource limits + egress control; isolation from the host                                                                                                              | core  |
| 4  | Accidental deletion/overwrite of the wrong thing                                                                                                                         | container: the write boundary is the project copy only                                                                                                                             | core  |
| 5  | Tampering with persistence paths (git hooks, `.gitconfig`, shell startup files, agent config) so code runs later outside the box                                         | those paths are read-only inside the box; hooks do not travel over `git fetch`                                                                                                     | core  |
| 6  | Tunnel out through a linked git worktree (shared `.git`)                                                                                                                 | clone instead of worktree: a fully independent copy in the box                                                                                                                     | core  |
| 37 | Path traversal when applying model output (api executor): "write `../../…` or `.git/hooks/…`" — RCE on the host without a single gnome process                           | writes only inside the working copy, `.git/**` forbidden, symlinks resolved before writing, contract test                                                                          | core  |
| 38 | Checkout of an untrusted branch into the factory filesystem (symlinks, hooks, historical checkout CVEs)                                                                  | invariant: untrusted content materializes only inside the task environment; the factory reads bare git objects; `core.hooksPath` points at an empty location in all factory copies | core  |
| 39 | Poisoning the box OUTSIDE the working copy (Gradle init scripts, wrapper scripts shadowing PATH entries, hooks of the box's clone) so checks lie — invisible in the diff | the `reuse` / `verify-in` settings: a fresh box built from the attempt commit resets everything outside the branch and proves the branch self-sufficient                           | core  |
| 42 | A local clone shares objects with the factory clone via hardlinks — corrupting the factory repository below the git level                                                | `git clone --no-hardlinks` is mandatory                                                                                                                                            | core  |

## Secrets

| #  | Threat                                                                             | Closed by                                                                       | Where                           |
|----|------------------------------------------------------------------------------------|---------------------------------------------------------------------------------|---------------------------------|
| 7  | Inheriting the whole environment (including unrelated cloud keys)                  | environment-variable allowlist (container environment is empty by construction) | core                            |
| 8  | Tracker token leaking into command checks (`./gradlew test`)                       | declared scrub list on `CommandProcessRunner`                                   | shipped (`add-claim-heartbeat`) |
| 9  | A secret in the agent's environment gets phished by injection (the Devin incident) | principle: not a single real secret in the box                                  | core                            |
| 10 | Push rights / push credentials in the gnome's hands                                | push monopoly: the gnome commits inside, the factory pushes outside             | shipped (reinforced by core)    |
| 11 | Setup-phase secrets surviving into the gnome's work phase                          | two phases: secrets exist only during setup, wiped before gnome start           | hardening                       |

## Network

| #  | Threat                                                                                                                                                  | Closed by                                                                          | Where                          |
|----|---------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|--------------------------------|
| 12 | Exfiltration of stolen data to an arbitrary address                                                                                                     | egress default-deny + allowlist (the guard)                                        | core                           |
| 13 | Downloading and running malicious code from the network                                                                                                 | the same allowlist (package registries / AI provider / git only)                   | core                           |
| 14 | `HTTP_PROXY` is only a convention, bypassed by a direct connection                                                                                      | second layer: the box has no network route out except through the guard            | core                           |
| 15 | An allowed host is an open door (exfiltration into a foreign repository on github.com)                                                                  | narrow list; L7 rules (github read-only)                                           | core (narrow) / hardening (L7) |
| 16 | The AI-provider channel is irremovable; a *foreign account* (an allowlist grants access, it does not filter content); the documented Anthropic incident | TLS interception: pin our own API key, strip foreign authentication headers        | hardening                      |
| 17 | Envelope swap (domain fronting) invisible without TLS interception                                                                                      | TLS interception at the guard (mitmproxy)                                          | hardening                      |
| 18 | DNS as an exfiltration channel (tunnelling)                                                                                                             | DNS is resolved by the guard, not by the box                                       | core                           |
| 19 | Silent failure of the protection (allowlist silently ineffective)                                                                                       | mandatory egress self-check at box start                                           | core                           |
| 40 | Exfiltration by encoding data into request paths sent to allowed registries                                                                             | artifact depot: the gnome talks only to the depot; the depot itself talks upstream | depot                          |
| 41 | A freshly-poisoned package version (the s1ngularity class) arriving from upstream                                                                       | cooldown (only versions older than N days) + quarantine / CVE block at the depot   | depot                          |

## Resources and isolation

| #  | Threat                                                                            | Closed by                                                                                  | Where                              |
|----|-----------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|------------------------------------|
| 20 | Host resource exhaustion (busy-looping on CPU, memory leak, fork bomb, disk fill) | container limits (cgroups: CPU / memory / process count / disk)                            | core                               |
| 21 | Weak mutual isolation of parallel tasks (shared kernel; one Docker VM on macOS)   | container per task; the macOS residual risk is accepted; VM-grade adapters are the upgrade | core (accepted) / colima-vm, cloud |
| 43 | Silent isolation degradation (the "vz silently fell back to QEMU" class)          | the startup self-check verifies the isolation mechanism too, not only the network          | core                               |

## Reward hacking and rules integrity

| #  | Threat                                                                                                       | Closed by                                                                                                                   | Where |
|----|--------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|-------|
| 22 | The gnome grades its own work (weakens criteria, tests, limits)                                              | the "law of the task" (`.gnomish/`) is taken from the factory clone, unreachable to the gnome                               | core  |
| 23 | Repo-local config (`.gnomish/`) as untrusted input executed before trust is established (Anthropic incident) | it executes only inside the disposable box                                                                                  | core  |
| 44 | The repo/task requests a weaker sandbox (`executor: host` or `reuse` in a stage manifest)                    | ownership asymmetry: the repo may only tighten; bindings and any loosening are operator-only; reconciliation is fail-closed | core  |

## Docker and nested execution

| #  | Threat                                                                                                  | Closed by                                                                   | Where            |
|----|---------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|------------------|
| 24 | Mounting `docker.sock` / the node's CRI socket into the box = handing over the host or node             | banned throughout; the Docker-strategy ladder (steps 0–3) instead           | core / hardening |
| 25 | A privileged Docker-in-Docker sidecar removes isolation (banned by Kubernetes PodSecurity `restricted`) | banned throughout; sysbox / Kata / kubedock without `privileged`            | per ladder step  |
| 26 | CI secrets on gnome branches (a workflow triggered by a push to `gnomish/*` sees repository secrets)    | `gnomish/*` workflows carry no privileged secrets; `GITHUB_TOKEN` read-only | gha              |

## Cloud and k8s

| #  | Threat                                                                                 | Closed by                                                                                    | Where |
|----|----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|-------|
| 27 | The cloud metadata service (`169.254.169.254`): SSRF → the account's cloud credentials | metadata endpoint blocked by guard/firewall, mandatory                                       | cloud |
| 28 | A persistent volume as a second source of truth breaks the stateless factory           | principle: a volume is only a cache for warm resume, deletable without loss of correctness   | cloud |
| 29 | Data (code, prompts, history) leaves for the cloud — a trust and jurisdiction decision | the operator's informed choice; usually acceptable for a repository already hosted on GitHub | cloud |
| 30 | Image pull is performed by the node's kubelet, outside the pod's network policy        | cluster registry policy                                                                      | cloud |

## Tool trust

| #  | Threat                                                                                                                                                                             | Closed by                                                                                                                                                                                                             | Where                          |
|----|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------|
| 31 | A key holder (gateway) with a bad security history is dangerous in itself (the OmniRoute example: package blocking, default admin secret, fail-open behavior)                      | a mature local OSS gateway (LiteLLM-class); selection criteria in the hardening design                                                                                                                                | hardening                      |
| 45 | Provider server-side tools (web search / web fetch executed on the API side): the model fetches an arbitrary URL with the provider's infrastructure — past the guard and the depot | tool policy at the gateway: the gateway is a protocol endpoint (not a MITM), it sees request bodies in the clear without TLS interception; server-side web tools are stripped unless the stage explicitly allows them | hardening (groundwork in core) |

## Verdicts, findings and data sinks

| #  | Threat                                                                                                                                          | Closed by                                                                                                                                                                                                          | Where |
|----|-------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------|
| 32 | Findings of any check (judge / external / command) as an injection vector: their text reaches the retry prompt, the tracker, the logs           | a single findings funnel: fixed schema, branching only on passed / exit code, log sanitization, fenced tracker publication (untrusted text quoted as data); the retry sink is protected by the box by construction | core  |
| 33 | Injection against the judge itself through artifacts ("ignore the criteria, pass it")                                                           | concrete criteria, hard delimiters, N votes for critical stages                                                                                                                                                    | core  |
| 34 | The gnome rewrites an external check's definition in its own branch (workflow file, SonarQube properties) — grading itself via CI before review | the pin check runs first: definition files are byte-compared against the base branch; server-side definitions where possible                                                                                       | core  |
| 35 | Forging an external check's status with a token                                                                                                 | poll platform-authored verdicts (the workflow run's conclusion), not check-run statuses; no tokens in the box                                                                                                      | core  |
| 36 | Gigabytes of findings/logs as a resource attack on the factory                                                                                  | size limit at read/poll time, only the tail is kept                                                                                                                                                                | core  |

## Cross-cutting principles

- **Filesystem isolation and network isolation only work together** — each one
  alone is bypassed through the other. Hence egress control shipped in change A
  together with the container, not later.
- **When in doubt — closed (fail-closed)**: sandbox failed to start, self-check
  failed, guard unavailable → the task does not start or reports an
  infrastructure failure; never "run unprotected".
- **The gnome cannot change the rules of its own confinement** (read-only
  sandbox config, hooks, and the `.gnomish/` law).
- **Not a single real secret in the box** — only a placeholder / virtual key;
  real keys and push rights live with the factory outside.
- **Prompt injection is a given** (adaptive attacks succeed >85% of the time):
  we defend with the environment, not with "the gnome won't think of it".
- **The sink method**: data from an untrusted source is dangerous
  only at the sinks where someone interprets it; for every new channel,
  enumerate the sinks and make each one inert. Mandatory in every design.
- **Untrusted content never materializes in the factory filesystem**:
  checkout happens only inside the task environment; the factory
  reads bare repositories and speaks git transport; factory git executes no
  hooks.
- **Ownership asymmetry**: the repo declares needs and may only
  tighten; adapter bindings and any loosening are operator-only decisions; the
  factory reconciles fail-closed.
