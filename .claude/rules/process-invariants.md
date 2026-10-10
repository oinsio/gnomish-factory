# Rule: development process invariants

These rules apply to ALL work in the project, regardless of artifact type.

## Archived changes are immutable

Never edit files in `openspec/changes/archive/`. If a change needs correction, create a new change with `Supersedes: <old-change-name>` in the proposal.

## File size limit

Target 100–120 lines per file; 200 is a hard cap, used only when splitting would hurt clarity. Long files degrade AI context quality. One file = one thing.

The limit is a proxy for low coupling, not a goal: **splitting a file must split a
responsibility**. A split that leaves the halves reaching into each other's fields, passing
20-parameter bundles, or delegating in a circle has made coupling worse while satisfying the
number — that is a violation of this rule, not compliance ("extracted for file size" with no
ownership transfer is the tell). If no responsibility boundary exists, reduce the class's
responsibilities first; the line count follows.

**A split that converts fields into parameters is not a responsibility split.** When an
oversized class holds collaborators, moving part of its code into a static helper that
receives those collaborators as arguments leaves ownership where it was and turns the field
set into a parameter list that every call re-supplies — and that each further split
multiplies along the chain. The correct transformation gives the extracted half the
collaborators as **fields**: it becomes an instance, constructed once with the equipment it
uses (grouped into a parameter object when the set is large), whose methods take only the
per-call job. The failure this clause exists for: the take chain's `TakeFreshClaim`,
`TakeContainerFreshClaim`, `TakeWorkRouter` and `TakeClaimAndWorkFactory` were each extracted
"for file size" as static recipes, and four signatures ended up carrying the same eleven
collaborators, up to sixteen parameters each (`introduce-slot-wiring`, which turned them into
instances holding `SlotWiring`).

**The parameter object stops at the last relay.** A component that only passes the equipment
on takes the object whole; the leaf that actually uses it declares exactly the members it
uses and is constructed from explicit reads of the object (Fowler's stated exception to
Preserve Whole Object; the interface-segregation principle). A leaf handed the whole object
depends on members it never touches, and its spec has to build placeholders for them — the
"too many dependencies" signal of *Growing Object-Oriented Software*. A leaf that would need
more than seven members gets its own facade over the cohesive cluster it uses, exposed by the
parameter object as one accessor — never the whole object. Precedent: `TakeEngineExecution`
and `TakeContainerEngineExecution`, built per run from `SlotWiring` reads
(`introduce-slot-wiring`, design, exemption row).

## Parameter count limit

A constructor or method with **more than 7 parameters** must take a parameter object instead
(precedent: `EnginePorts`, `TakeClaimAndWork`). Two adjacent parameters of the same type
(`Path, Path`; `String taskId, String branch`) are a transposition hazard at any count —
prefer distinct value types or a parameter object.

**The limit is enforced at compile time** by the project's own Error Prone check,
`ParameterCountLimit` (the `build-checks` included build), which `java-conventions` places on
every module's processor path: an eighth parameter fails `compileJava` in the module that
declares it, with the count, the limit and the transformation to apply in the message. No
module configures the check and none can opt out; the only production Java outside it is the
check's own build, which cannot apply the convention that would put the check on its own
processor path. The wiring is pinned by `ParameterCountGateFunctionalSpec` in `build-logic`,
the semantics by `ParameterCountLimitSpec` beside the check
(provenance: `add-parameter-count-gate`, FR7).

**Two exemptions, decided from the syntax tree, never from a list:**

- **A record's constructors** — canonical, compact or explicit. The record *is* the parameter
  object the rule asks for, so its constructor is the parameter object being declared. The
  exemption stops there: an ordinary method housed in a record chooses its own signature and
  is counted like any other method.
- **An overriding or implementing method** — it does not choose its signature; the declaration
  it overrides did.

Nothing else is exempt by construction — not dependency-injection sites, not constructors at a
higher threshold, not composition roots. Composition roots are fixed, not excused (the facade
clause below).

**A genuine exception is one declaration carrying `@ParameterLimitExemption(reason = "…")`**,
with a non-blank reason written on the declaration it excuses. There is no bulk form: the
annotation targets methods and constructors only, the check ignores `@SuppressWarnings`, and no
allowlist file exists — so a grep for the annotation's name is the complete, current list of
the gate's exceptions with their justifications. The reason is the record of why this
signature cannot take a parameter object; a blank one is reported like an eighth parameter, and
so is an annotation on a declaration that would pass without it, so the list cannot go stale. If
exemptions ever accumulate past a handful, revisit the limit in a change rather than grow the
list.

**When a record earns the exemption, and when it is a bag wearing it.** The gate counts; it
cannot tell a parameter object from an argument bag, and a count gate rewards bags. Review
owns that call, by three tests the preceding refactorings used — a record that fails one is a
bag, and bundling into it is the shape this rule forbids:

1. **The group recurs** across several signatures of the code that *uses* it, not only at the
   one site that was over the limit.
2. **It names a domain concept** the reader already has — a term with a glossary entry
   (`AgentRoundEquipment`, `BoxTiming`, `EligibilityInputs`), not `…Args` or `…Params`.
3. **It absorbs behavior**: an invariant in its constructor, a method beyond accessors, or
   the delete-one test — remove one component and the rest still name a thing.

**Composition sites take facades, not parameter objects** (`docs/adr/0010-facade-over-parameter-object.md`).
A parameter object fits a group that recurs across signatures of code that *uses* it; the
argument list of a root, an assembly or a command constructor recurs nowhere, so bundling it
hides the responsibility count without reducing it. Before extracting a facade there, answer
the ADR's three questions in the change's single-owner table — used together, a method beyond
accessors, a name the reader already has — and record a rejection when one fails. A consumer
that needs part of a facade takes a role interface the facade implements, never members read
back out through accessors; a facade carrying a credential seam exposes no accessor for it,
and the seam's reach (the files declaring it) is pinned by an architecture spec.

**Three clauses for where a dependency goes** (provenance: design D22 of
`supervise-daemon-loops-and-embed-dashboard`, after the limit pushed real time into hidden
fields; after van Deursen and Seemann's *DI Principles, Practices, and Patterns* and GOOS):

1. **Field or parameter?** A constructor takes the collaborators and configuration stable for the
   object's lifetime; a method takes the data of one operation. A relay that only hands members
   on is the defect, not the count (`TakeOutcomeDispatch` holds the slot's retry and abort fuse
   as fields and takes one run's `TerminalTransitions` per call).
2. **A shorter constructor supplies only a Local Default** — a no-op or a Null Object of its own
   module, never another module's adapter (a Foreign Default, Seemann's *Bastard Injection*). A
   "test constructor" is that defect by another name; `grep -rn "test constructor"
   --include='*.java'` lists the survivors, each removed when its class is next touched.
3. **A seam a spec fakes is a role interface in the owning module**, never a JDK functional type
   ("only mock types you own"): `ContainerRuntimeProbe` and `TerminalPresence` replaced two
   `BooleanSupplier` seams, and `TimeSourceOwnerBoundarySpec` bans `BooleanSupplier` in
   `application/src/main` and `bootstrap/src/main`.

## Immutable after construction

An object is fully initialized by its constructor: no `init()`, `start()`-before-use without
a guard, no `attach*`/setter that completes wiring afterwards. When an assembly cycle
genuinely forces post-construction injection, the field is `volatile` (or otherwise safely
published), the reader handles the not-yet-attached state explicitly, and a comment names the
cycle that forced it. Rationale: the factory runs work on concurrently started virtual
threads; an unpublished write to a plain field is a silent data race, and "constructed but
not started" is a silent no-op.

## Module boundaries

Modules expose an explicit public API; never import from internal files of a sibling module. (Concrete mechanism depends on the tech stack — to be refined once the stack is chosen.)

## Change naming

Use `kebab-case-descriptive` for change names: `add-tracker-port`, `fix-claim-race`, `refactor-stage-engine`. Never use generic names like `update`, `changes`, `wip`.

## Context hygiene

Clean the AI agent context before running `/opsx:apply`, especially for large changes. Stale context leads to incoherent code.

## Artifact layering

Lower-layer artifacts always reference IDs from upper layers:

1. PRD (`proposal.md`) — what and why
2. Domain spec + behavior spec — entities, rules, use cases
3. Contract spec (ports) + ADR — how components connect
4. Code + tests — implementation

If an artifact has no upward reference — it is either unnecessary or missing a link.

## Change scope

One change = one initiative, completable in 1–4 weeks. If a change grows beyond that, split it into smaller changes.

## Git commits

The AI agent NEVER creates git commits in this project — no `git commit`, `git commit --amend`, or any other history-writing command. Instead, after completing a unit of work, the agent recommends a commit message based on the diff since the last commit (`git status` / `git diff HEAD`); the human reviews and commits. The recommendation should summarize what changed and reference the OpenSpec change / requirement IDs where applicable.

Keep the recommended message short: a Conventional Commits subject line (≤ ~72 chars) plus, only when the "why" is not obvious from the subject, a brief body of 1–3 lines. Don't restate the diff, re-explain the rationale at length, or pad with metrics — the diff and the change artifacts already carry that. Reference the OpenSpec change / requirement IDs on a trailer line.

## Documentation language

All project documentation, specs, rules, code comments, and commit messages are written in English.

One exception: `temporary-docs/` is scratch space, not part of the durable record (see "No
references to temporary files" below), so a note there may be written in the language the
human converses in. A non-English file adds its ISO 639-1 code before the extension
(`explore-notes-base-ref.ru.md`); an English one keeps plain `.md`. Anything promoted from
there into `docs/` or `openspec/` is translated to English on the way.

## No jargon; domain terminology is welcome

Documentation and discussions use plain, precise language — no slang, no
insider jargon, no cutesy shorthand that a newcomer (human or AI) would have to
decode. Established **domain terminology** is not jargon and is actively
encouraged: project terms (**factory**, **gnome**, **box**, **guard**, ...) and
industry terms (egress, allowlist, fail-closed) are precise names for real
things. The distinction: a domain term has a written definition the reader can
follow; jargon relies on tribal knowledge.

The project's terms and abbreviations are defined in `docs/glossary.md` — the
normative ubiquitous-language dictionary, grouped by bounded context:

- A change that introduces a new domain term adds its glossary entry in the
  same change; a change that shifts a term's meaning updates the entry.
- Domain code, ports, and fields are named by glossary terms; renaming the
  concept means renaming the code.
- Banned synonyms listed in glossary entries (*Never:* ...) must not appear
  anywhere — code, docs, or discussions.
- A term used only within one document may instead be defined where it is
  introduced; promote it to the glossary once a second document needs it.

## No references to temporary files

`openspec/**` artifacts (proposals, designs, specs, tasks) may only reference project files that live under `docs/` — never scratch/explore locations, which are not part of the durable record and may be deleted at any time. When an idea needs to be cited from an ephemeral note: either inline the relevant meaning directly into the OpenSpec artifact, or propose creating a durable `docs/` file (an ADR, an operator guide, a scope note) and reference that instead.
