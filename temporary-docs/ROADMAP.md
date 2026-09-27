# Roadmap: after the active changes land

Assumes every change currently in `openspec/changes/` is implemented:
the full `run` / `take` / `serve` loop with claim leasing and the WIP
limit, the four sandbox adapters (container, Colima VM, k8s, GHA), the
egress guard with TLS interception, the AI gateway with virtual keys,
and the artifact depot. What remains falls into two groups: engine gaps
no active change covers, and work that lives outside the factory's code.

## Engine gaps (candidate changes)

### 2. Delivery PR — `add-delivery-pr`

Opening the pull request can already be a pipeline stage: the `deliver`
stage of `gf-tests` (see `docs/reference-pipelines.md`) opens or updates
it and checks the published body. Open question: keep this in pipelines,
or move it into the factory as a built-in step after delivery.

### 3. `api` executor — `add-api-executor`

Pipelines with `executor.type: api` are still rejected at startup. The
ai-provider port with direct API adapters (Claude, OpenAI, Gemini, …) —
a third of the README's port table — is not built. The gateway provides
multi-provider routing, but only as protocol translation under the same
agent CLI.

### 4. Packaging and service operation — `add-factory-packaging`

The launcher and release archive come with `add-release-pipeline`; a factory
Docker image is its NG2. Real `serve` operation still needs a service
wrapper (factory Docker image / systemd unit / launchd) and a
deployment story for the always-on companions: guard, gateway, depot
(compose recipes are promised by their changes, but operating them is
its own surface).

### 6. Additional trackers

Jira is declared as planned in the README; only GitHub (plus the
in-memory reference) exists. Not a blocker, but part of the stated
picture.

### 8. Ограничители на запуск стадии/задачи и аналитика

1. В токенах
2. Может ещё как-то (надо продумать)

### 9. Сделать формирователь контекста для AI агента

1. Выходной формат
2. Может что-то ещё (надо продумать)

## Work outside the factory's code

### 7. Reference pipelines — the largest item

The factory is an engine; the target project's `.gnomish/` content
(stages, `instructions.md`, `acceptance.md`, judge criteria) lives
outside it. One reference exists (`gf-tests`, Java/Gradle/Spock with
OpenSpec); more stacks are to come, collected in
`docs/reference-pipelines.md`. Gnome output quality hinges on prompt
quality and verifiable acceptance criteria. This is iterative, empirical work
against real runs.

### 8. Task-authoring discipline

An autonomous conveyor needs well-specified issues: tasks sliced to a
size a gnome can carry, and a human who drains escalations (the WIP
limit deliberately makes that human the bottleneck). A process to
establish, not code to write.

### 9. Cross-branch conflict management

`add-factory-serve` explicitly scopes this out (its NG4): W bounds how
many branches are open, not whether they merge. With N parallel tasks,
branches will diverge — pipelines need a rebase stage and/or slicing
discipline, or manual merge work eats the autonomy gains.

### 10. Deferred tool re-verification

Nearly every sandbox change carries "re-verify at implementation
start": gateway choice (LiteLLM vs Bifrost), depot product (Nexus vs
per-ecosystem lightweights), kubedock maturity, Colima/Lima state. The
market moves; some decisions will be re-taken during implementation.
