# Rule: what to run after one task, and what only at the end

Applies to every implementation that proceeds task by task — `/opsx:apply`,
`/opsx:apply-sequential`, `/fix-from-audit`, and the factory's implement and fix-code
stages — for whoever runs it: the orchestrator, a subagent, a human.

## The failure this rule exists for

Subagents of `/opsx:apply-sequential` each ran their module's full `check`, read from
`testing.md`'s 100% mutation target and from tasks that say "the module's PIT gate green".
PIT's default scope is the whole branch since `main`, so every task re-mutated every earlier
task's classes (2471, 439 and 890 mutations in three modules), and `:bootstrap:test` reran
2267 specs, the Docker and Gitea end-to-end suites among them. One change spent hours in
repeated verification — 1 h 24 min in one root `check`, 41 min in one scoped PIT run —
instead of in its tasks.

## After each task: the task's own scope

1. **Compile and format** the modules the task touched:
   `./gradlew :<module>:spotlessApply :<module>:compileTestGroovy` (compilation runs Error Prone and
   NullAway).
2. **Run the specs the task wrote or changed, and the specs of the classes it changed** —
   by name: `./gradlew :<module>:test --tests '<Spec>'`. Never a module's whole `test` for
   `:bootstrap`; for a small module the whole `test` is fine.
3. **Mutate the task's own classes only**, never the branch:
   `./gradlew :<module>:pitestVerifyAllKilled -PpitScope=<fqcn>,<fqcn>` — a comma-separated
   list of the production classes the task changed (a glob such as `pkg.Foo*` covers nested
   classes). A survivor is fixed now, while the task is fresh, by the same rules as any
   other (`testing.md`). A task that changed no production Java skips this step.
4. **Architecture and build specs** the task's own text names — run them by name, like
   step 2.
5. **Specs whose subject is the platform run on Linux too.** A spec of OS or JDK behaviour —
   a real process, a pipe, a signal, a process group, the launcher — is run by name through
   `scripts/linux-specs.sh` as well, the same `--tests` arguments as step 2: the developer's
   machine is macOS and CI is Linux, and a fact that holds on one may not hold on the other
   (`design-decisions.md`, "A platform fact is measured on every platform CI runs").

A task's "PIT gate green" or "`check` green" wording is met by these steps for the task's
own scope; the whole build is proven once, below.

## At the end: the whole build, once

**The cheapest platform signal first.** Before the root `check`, and before any push, the
modules that hold platform specs (step 5) are run through `scripts/linux-specs.sh` — minutes,
against an hour for the root `check` and a push for CI. CI stays the last confirmation, never
the first Linux signal. The failure this exists for: the Linux failures of
`fix-terminal-receipt-convergence` were found by CI only, twice (runs 38879822474 and
39117545428), and the next change planned root `check` → push → CI again until the operator
asked for the container run (`make-pipe-reads-non-parking`, 2026-10-19).

When every task is done, run the full root `./gradlew check` — no `--tests`, no
`-PpitScope` — and fix what fails. It is the gate that proves cross-module effects and
catches what a per-task scope missed. It runs once per implementation, not per task, and
again only after a fix. Repeating it on an unchanged tree is cheap — the build cache and
up-to-date checks skip every task whose inputs did not change — so a later gate that runs the
same `check` (CI, a pipeline stage's own check) costs seconds, not another hour.

## What this rule does not change

- The gates themselves: the 100% mutation target, the exemption bars of `testing.md`, the
  final root `check`. Only *when* the whole build runs changes.
- A task whose text asks explicitly for a whole-module or whole-tree run (a measurement, a
  timing baseline) runs exactly that.
