# Design: narrow-branch-mutation-scope

## Context

See proposal.md — Why. What shapes the approach:

- The widening rule has one owner today: `ChangedSources.classify` in `build-logic`
  (`build-logic/src/main/groovy/com/github/oinsio/gnomish/buildlogic/ChangedSources.groovy`,
  lines 56–63) marks a module `SHARED_FIXTURE` when any touched path starts with
  `test-fixtures/src/` and `TEST_CHANGE` when a touched path lies under a module's `src/test/`.
  The result travels as `MutationScope.widened` (a `Map<String, String>`, module path → reason,
  `MutationScope.groovy:41`) and has exactly two readers: `pitest-scope-conventions.gradle`
  (`wholeModule = mode == ALL || widened.containsKey(module)`, line 106, and the summary form
  `whole module (<reason>)`, line 111) and the functional fixture `MiniScopeRepository`
  (`widened: obtained.widened`, line 171), which prints the obtained value for the specs to read.
- The specs that pin the rule: `MutationScopeFunctionalSpec` ("FR3, M3: a test change widens its
  module, a shared fixture change widens every module", lines 124–135, marker "A shared fixture
  change mutates everything"; the nested-module feature at lines 137–152 asserts `widened` for a
  nested spec edit) and `MutationScopeConventionsFunctionalSpec` (lines 80–91, markers "A weakened
  spec is caught" and "A branch run explains itself", asserting the line `mod-a: whole module
  (test change)`).
- The `ALL` mode is decided before any diff is read (`MutationScopeSource.groovy:109–113`) and
  never populates `widened`; `pitest-scope-conventions` derives the whole-module target from the
  mode alone in that case. So the field carries information in `BRANCH` mode only, and after this
  change it would carry none.
- `verification-scope.md` ("At the end: the whole build, once") promises a root `check` that
  "proves cross-module effects"; with the narrowed scope that run proves the changed classes
  only, and the rule text has to say which owner proves the rest.
- The nightly workflow, its issue script and `CiScopeOwnerSpec` are untouched; the sibling change
  `parallelize-nightly-mutation-run` owns them and its delta MODIFIES "Scheduled whole-tree
  mutation run" too — the sync order is this change first (it only adds a scenario to that
  requirement), the sibling layered on it.

## Goals / Non-Goals

**Goals:** one decision that removes the widening and everything that only existed to carry it
(FR1, FR2, FR3); the deleted-class behaviour and its test unchanged (FR4); the documentation
naming the new sole owner and the pickup condition (FR5); the premise behind the removal verified
in the record, since the change narrows a scenario the archived design promised
(`spec-mismatch.md`).

**Non-Goals:** any redesign of what a test change should widen (proposal NG1), any workflow
change (NG2), any change to modes `ALL`/`EXPLICIT`, to the base resolution or to the fallback
direction (NG6).

## Decisions

**D1 — The widening is deleted, not disabled: `ChangedSources` classifies production classes
only, and `MutationScope.widened` with its two reasons leaves the value type.** `classify`
drops the `touched` pass and, with it, its `deleted` parameter: that pass was the only reader of
the deleted paths (`ChangedSources.groovy:55`), so `classify` takes the present paths and the
module map only, and `MutationScopeSource.branch` stops collecting the deleted list. A deleted
production file still contributes nothing (FR4) because `MutationScopeSource` keeps `D` entries
out of the present list (`MutationScopeSource.groovy:141`), not because `classify` is told about
them. `MutationScope.branch` takes the class map only; the three
factories stop building an empty widening map; `pitest-scope-conventions` decides
`wholeModule` from `mode == ALL` alone and the branch summary has one form, `N classes`;
`MiniScopeRepository` stops printing `widened`. *Rationale:* alternative zero
(`design-decisions.md`): once no rule populates the field, what varies per module in branch mode
is the class set and nothing else, so a field that is always empty is a lie the next reader has
to disprove, and a `whole module (reason)` branch in the module script is dead code — an
unkillable mutant under the 100 % gate were it Java, and an unreachable branch in Groovy either
way; a parameter no line reads is the same lie one level down. Deleting it also makes M4
greppable. *Alternative rejected:* keep the field and the readers,
make `classify` never fill it — "disabled, easy to re-enable". The re-enabling change would have
to rewrite the rule anyway (NG1 asks for a computable blast radius, not the old prefix test), and
an empty map kept "for later" is the escape hatch `implementation.md` item 3 forbids: a future
contributor fills it from a new place and the module script silently honours it.

**D2 — This is an amendment of D4 of `scope-pit-locally`, recorded with its verified premise,
and the scenario it narrows is replaced, not reworded.** D4's rationale was FR3 of that change:
"a weakened test can hide a survivor anywhere it reaches", with the shared-fixture rule
"deliberately the whole tree". The premise that made the rule affordable — that a shared-fixture
edit is rare — is refuted by the merge table in the proposal (six of six code-bearing merges
since October touched `test-fixtures/src/`; the measured consequence, 3 h 08 min against
3 h 51 min for the whole tree, is in the proposal's Why). The promise D4 made is kept by the
nightly run (FR6 of the same change), which was already the whole-tree owner for the case D4
could not cover (a mutant in an unchanged file that stops dying because of a change elsewhere);
a weakened fixture is the same shape — a change elsewhere — and lands in the same scenario class,
now stated by the new scenario "A weakened test or fixture is caught within a day". Per
`spec-mismatch.md` the scenario "A weakened spec is caught" is not narrowed in place: its
requirement is REMOVED by this decision, and NG1 names the condition under which a successor
re-states it against the branch gate. *Rationale:* the rule exists so that a scenario is changed
by a decision with a verified premise, never by an implementer making code pass; this decision
is that review, and the premise is one `git show --name-only` per merge. *Alternative rejected:*
narrow only the shared-fixture rule and keep the per-module test-change rule. The per-module
rule costs the module's whole tree on every task that touches its specs — every task does, and
`:adapters:git` alone is 18 minutes of mutation locally and about an hour on the CI runner — so
it leaves most of the cost in place for a guarantee the nightly already gives; and two rules
with two owners for one promise is the two-recovery-owners shape (`crash-consistency.md`,
item 3).

**D3 — `verification-scope.md` names the nightly run as the whole-tree owner and does not ask
for a whole-tree run by hand before a merge (proposal Q1).** The end-of-change root `check`
keeps its role (cross-module compile, specs, architecture gates, the mutation gate on the
changed classes); the sentence "the gate that proves cross-module effects" is qualified to say
that mutation rot in unchanged classes is the nightly's finding, within a day, as a
`nightly-mutation` issue. *Rationale:* a recommended `-PpitScope=all` before merge is the
widening rule re-introduced as a convention — unenforced, and paid by exactly the changes this
change exists to speed up. The accepted cost is the one-day bound, and the rule says so.
*Alternative rejected:* recommend the whole-tree run for changes that edit `test-fixtures`
"heavily" — every change does, and "heavily" is not a condition anyone can check.

**Sync surfaces: none — this change adds no parallel implementation and touches no declared
pair.** The scout: `grep -rn "Kept in sync with" build-logic/src/main` lists
`TestTimeInjectionCheck`, `AdversarialGitConfig` and `TestDockerEnvironment`, none of which this
change touches; the widening rule has one implementation (`ChangedSources`) and this change
deletes it rather than adding a second; the registry rows of `manual-sync-pairs.md` name no
mutation-scope class. The developer guide and the rule file describe the behaviour in prose and
are updated in the same change (tasks 3.x), but prose beside a single code owner is
documentation, not a second implementation.

**Single-owner mechanisms: none.** The mutation scope already has its single owner
(`MutationScopeSource`, D1 of `scope-pit-locally`, enforced by `CiScopeOwnerSpec` for the
workflows and by the `pitScope` contract for the build); this change removes a rule from that
owner and introduces no mechanism of its own. The consumer list of the removed value is in D1
(`pitest-scope-conventions.gradle`, `MiniScopeRepository`) so the apply sub-agent's old-way sweep
is `grep -rnE '\.widened|\bwidened( =|$|[:,).[])|SHARED_FIXTURE|TEST_CHANGE|whole module \(' build-logic/src`,
expected empty (M4). The pattern matches the identifiers, not the English word: `widened` in its
ordinary sense (`TestTimeInjectionCheckFunctionalSpec.groovy:19`, `test-conventions.gradle:169`)
is not this change's and stays.

**Deferrals of archived proposals (constraint 7 of `/opsx:propose-checked`):**

- `scope-pit-locally` NG1 (no PIT incremental analysis) — not met; re-deferred inside this
  change's NG1 with the same reason (an agent cannot tell a reused verdict from a fresh one).
- `scope-pit-locally` NG3 (no `MutationEngineLock` relaxation, "deferred until the effect of
  scoping is measured") — the condition is measurement; this change measures (M1, M2) and the
  result is that concurrent PIT runs stay rare with a real scope, so the lock stays (NG4 here).
- `scope-pit-locally` NG4 (no line-level scoping) — not met; re-deferred (NG5 here).
- `kill-expensive-mutants` NG1, NG4, NG5 — not met (no threshold, no cost gate, no other
  modules' mutants); NG1 and NG4 restated in NG3 here.
- `scope-pit-to-changed-files`, `adapt-build-load-to-hardware` — their proposals name `pitScope`
  and the budget; neither defers a gap this change meets (the budget's "memory budget, not a
  worker count" is untouched, NG4).

## Risks / Trade-offs

- **A weakened fixture reaches `main` and the nightly is red for days with nobody assigned**
  (the 14–19 October streak was six nights) → the sibling change cuts the nightly to about a
  quarter of its wall time, so the fix loop is one morning, not a day; the `nightly-mutation`
  issue is the queue; and NG1's pickup condition is written so that the widening returns when a
  branch-side whole-tree verdict becomes affordable.
- **A survivor found by the nightly belongs to a class nobody's branch changed** → the fixing
  branch names the class with `-PpitScope=<fqcn>` (explicit mode is unchanged), so the fix is
  scoped by hand to exactly that class.
- **A test-only change now skips every module's gate, and the agent reads "every module
  skipped" as a verdict** → the scope line still prints in that case (NFR-O1), the developer
  guide's branch paragraph says in one sentence that a test-only change mutates nothing and why,
  and the archive-time `checkSpecContract` still requires a marker for every scenario, so a
  deleted killing spec that carried a marker fails that gate, not silently.
- **`MutationScopeConventionsFunctionalSpec` loses the one feature that drove a widened run end
  to end** → its replacement feature asserts the skip and the one-form line for a test-only
  change (M3), which is the new behaviour end to end on a TestKit build.

## Migration Plan

Build logic only; no data, no deployment. Rollback is a revert of the change. The sibling
change `parallelize-nightly-mutation-run` is sequenced after this one for the shared MODIFIED
requirement; it can land without this change, but this change should not land without a nightly
that is green or being fixed, since the nightly becomes the only whole-tree owner.
