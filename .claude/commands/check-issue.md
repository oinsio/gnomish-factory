---
description: Verify whether a reported problem actually exists in the code; fix it only if confirmed, otherwise report that it does not exist
argument-hint: "<problem description>"
---

# Check Issue

Take a reported problem, **prove or disprove it against the actual code**, and act on the
result: fix it only when it is confirmed, report the disproof when it is not.

The report may come from a human, another agent, a review comment or a linter. None of those
are evidence. The claim is a hypothesis until this command reproduces it.

## Input

- `$ARGUMENTS` — the problem description. If empty, ask for it with AskUserQuestion.
- The description may name files, symbols, tests, log lines or an OpenSpec requirement ID.
  Treat every such pointer as a starting hint, not as a verified fact — a wrong file path in
  the report does not make the underlying problem non-existent.

## The rule that governs this command

**Do not fix what you have not reproduced.** A plausible-looking fix to a non-existent problem
is a worse outcome than a "does not exist" answer: it churns code, invalidates tests and hides
the real defect if there is one.

Equally: **do not dismiss what you have not investigated.** "I could not find it in 30 seconds"
is not a disproof. Only the checks in step 3 settle the question.

Agreeing with the reporter is not the goal. If the code is correct, say so plainly and cite why.

## Steps

### 1. Restate the claim in falsifiable form

Rewrite the report as a concrete claim of the shape:

> Under condition C, `component`/`file:line` does X, but should do Y.

Fill in whatever the report left implicit (inputs, state, which code path). If the claim cannot
be made falsifiable — it is a taste preference ("this is ugly"), an open question, or a feature
request — stop and say so: this command answers only claims that code can settle. Point the
user at `/opsx:propose` for feature work or `/code-review` for quality opinions.

If the claim has several independent parts, split it and carry each part through steps 2–5
separately; a report can be half true.

### 2. Locate the code under suspicion

Find the real implementation, not the one the report names:

- Locate the component by behavior (grep for the symbol, the message, the config key), and
  verify the named file/line still exists and still contains what the report assumes.
- Read the surrounding code fully — the guard that makes the problem impossible is often ten
  lines up, or in the caller, or in a `@ConfigurationProperties` validator.
- Check the ports/adapters boundary: a claim about an adapter may be constrained by the port
  contract or by a shared contract spec that already forbids the bad behavior.
- Check whether existing specs already assert the correct behavior (`grep` the test tree). A
  green spec asserting Y is strong evidence against the claim — confirm it really exercises
  condition C and is not disabled/ignored.
- For a claim naming an OpenSpec requirement ID, read the requirement in
  `openspec/specs/` or the change's delta spec; the spec, not intuition, defines "should".

For a wide search (behavior spread across modules, unknown naming), fan out read-only Explore
subagents in parallel, one per module or per naming convention, and collect their evidence.

### 3. Settle the question by execution, not by reading

Reading suggests; running decides. Pick the cheapest sufficient check:

- **Preferred — a failing spec.** Per `.claude/rules/testing.md` (TDD, red first), write a
  Spock spec for condition C asserting the correct behavior Y. Run it.
  - It **fails** → the problem exists, and you now own the red test that proves it. Keep it;
    it becomes the regression test of the fix.
  - It **passes** → the code already does Y under C. Either the claim is false, or your
    condition C does not match what the reporter meant — re-read the report before concluding,
    and try the next-most-likely reading of C.
- **Static-analysis claims** (nullability, unused code, format, dependency hygiene): run the
  gate that owns the rule — `./gradlew <module>:check`, or the narrower task — and read the
  actual output. Error Prone / NullAway / Spotless findings are the verdict.
- **Build/test-infra claims** (PIT survivor, coverage hole, flaky spec): reproduce with the
  real task; for flakiness, repeat the run enough times to distinguish flake from failure.
- **Runtime/integration claims** (subprocess handling, git workflow, tracker HTTP): use the
  layer the project already has for it — local bare git repo, WireMock, Testcontainers — per
  `testing.md`. Do not hand-wave a runtime claim from source reading alone.
- **Claims that genuinely cannot be executed** (a race window, a security fail-open reachable
  only in production, a documentation/diagram mismatch): settle by close reading and state
  explicitly in the report that the verdict is analytical, not reproduced, plus what would be
  needed to reproduce it.

Never delete or weaken an existing assertion to make the claim reproduce.

### 4. Verdict

Choose exactly one:

- **CONFIRMED** — reproduced. Go to step 5.
- **NOT REPRODUCIBLE** — the code does the right thing under the stated condition. Go to
  step 6. Do not change any code.
- **DIFFERENT PROBLEM** — the reported symptom is real but the stated cause is wrong, or the
  investigation surfaced an adjacent genuine defect. Report both: what the claim got wrong and
  what is actually broken, then treat the actual defect as CONFIRMED and continue to step 5.
- **ALREADY FIXED** — the problem existed but is resolved in the working tree or in a commit
  since the report. Cite the commit/diff and stop; no changes.

### 5. Fix (only when CONFIRMED)

1. Keep the red spec from step 3 as the regression test; if the verdict was analytical, add
   whatever test the situation does admit, and say so if none is possible.
2. Fix the **cause**, not the symptom. If the cause sits in another module or behind a port,
   fix it there rather than patching the observable edge.
3. Stay inside the scope of the confirmed claim. Unrelated improvements noticed along the way
   go into the report as observations, not into the diff.
4. Follow the project rules: file-size targets and module boundaries
   (`process-invariants.md`), English docs/comments, traceability comments if the fix
   implements a requirement ID (`traceability.md`), Mermaid over ASCII in any docs touched.
5. Re-run the red spec — it must pass — then run `./gradlew <module>:check` for every affected
   module (Spotless, Error Prone/NullAway, Spock, JaCoCo, PIT). A long PIT run is normal; do
   not abort it. If a gate fails, fix it or report it as failing — never as passed.
6. If the fix would require changing a spec's expectations, treat that as a design question:
   explain why the old expectation was wrong, or stop and ask.
7. **Never commit** (project invariant). End with a recommended Conventional Commits subject
   line per `process-invariants.md`.

### 6. Report

Always produce the report, both for a fix and for a disproof.

```
## Check Issue: <one-line claim>

**Verdict:** CONFIRMED / NOT REPRODUCIBLE / DIFFERENT PROBLEM / ALREADY FIXED

### Claim as tested
Condition C, expected Y, reported X. (Note any reinterpretation of the original report.)

### Evidence
How it was settled — the spec that was run and its result, or the gate output, or the
reasoning for an analytical verdict. Cite `file:line` for every code claim.

### What the code actually does        — only for NOT REPRODUCIBLE / DIFFERENT PROBLEM
The real behavior under condition C, with file:line and the guard/spec that enforces it.

### Fix                                 — only for CONFIRMED / DIFFERENT PROBLEM
Cause, the change made (file:line), the regression test added, gate results per module.

### Observations                        — optional
Adjacent things noticed but deliberately not changed, each with file:line.

### Recommended commit                  — only when files changed
<Conventional Commits subject, <=72 chars>
```

For a NOT REPRODUCIBLE verdict, state plainly that nothing was changed and why the reported
behavior cannot occur — including which existing test or guard rules it out. If the disproof
rests on an assumption about what the reporter meant, name that assumption so they can correct
it and re-run the command with a sharper condition.
