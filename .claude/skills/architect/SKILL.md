---
name: architect
description: Structured method for systemic-defect analysis, architecture elaboration, and architecture-decision review. Use when a bug looks like a class of bugs, when designing a cross-cutting mechanism, or when reviewing/challenging an architectural decision (ADR, design.md, proposal). Runs parallel code audits + external best-practices research in one wave, synthesizes in plain language, steelmans alternatives, checks for logic scatter, and lands results as durable artifacts.
---

# Architect: systemic analysis and design sessions

The argument after `/architect` is the subject: a defect report, an idea, or a
decision/ADR/change to review. Detect the mode from it; when ambiguous, ask one
question. Work in explore-mode stance: investigate and discuss, do not implement.

## Mode A — systemic defect (a bug that smells like a class)

1. **Verify first.** Never design against an unverified report. Fan out parallel
   agents to confirm each claimed defect against the actual code (file:line
   evidence, CONFIRMED / REFUTED / already-fixed). Check `git log` for recent
   fixes before assuming a report is current.
2. **Generalize the defect into a question.** "Kill between step N and N+1 —
   what does the next pickup see?" / "which reader assumes what?" Name the
   pattern; the pattern defines the audit scope.
3. **One parallel wave, not sequential waves.** Launch together:
   - code audits: every instance of the pattern across all subsystems and
     media (not just where the bug was found); ask agents to also list
     verified-SAFE places so the audit is a map, not a bug list;
   - external research agent #1: canonical patterns and their authoritative
     formulations for this problem class, with a critical GAP LIST against the
     emerging design;
   - external research agent #2: how comparable real systems solve it
     (their design docs and post-mortem), with a concrete BORROW LIST.
   Borrowing principles is cheaper than deriving them; derive only what the
   research does not cover.
4. Continue with the shared phases below.

## Mode B — architecture elaboration (new mechanism or contract)

Start at step 3 of Mode A: audit what exists (current owners of the concern,
their gaps) in parallel with the two research agents. Then the shared phases.

## Mode C — decision review (existing ADR / design / proposal)

1. Read the decision and every artifact it cites; check claims against the
   current code (decisions rot).
2. Steelman BOTH the decision and its strongest rejected alternative — a full
   trade-off analysis each, never a one-line verdict.
3. Run external research agent #1 (GAP LIST against the decision) and check
   consistency with all active changes touching the same regions.
4. Report: confirmed / needs-amendment / should-be-superseded, with evidence.

## Shared phases (all modes)

**Synthesis rules.**
- Plain language FIRST when introducing new concepts; codes and IDs after.
  If the user asks to re-explain simply, the previous message failed.
- Name root causes, not just findings: N findings usually share 2–4 causes.
- Rank by failure class (permanent stall > silent wrong/paid > bounded churn),
  not by discovery order.

**Decision discipline.**
- Any rejected major alternative gets a steelman first: argue FOR it honestly,
  then show exactly where it breaks on this project's constraints, then state
  what to borrow from it anyway. Expect and invite the user's challenges —
  their scenario walkthroughes and "check this against X" requests are design
  input, not interruptions; three such challenges improved the last session's
  design materially.
- Check every proposed mechanism for logic scatter: "how many places will
  implement this?" Each mechanism gets exactly one owner class; call sites
  pass policy as parameters. List existing twin implementations the design
  must consolidate or explicitly leave.
- Verify against active OpenSpec changes and recent commits: overlapping
  files, contradicted assumptions, required sequencing. Record the agreed
  change order in memory.

**Landing (durable artifacts).**
- Principle that outlives the change → `docs/adr/` (reference it from
  design.md, never restate).
- Future-work checklist → `.claude/rules/`.
- New domain terms → `docs/glossary.md` in the same change.
- Scope per `one change = one initiative`; name the cut line for overruns.
- Then hand off to `/opsx:propose` (artifacts) — this skill itself never
  writes application code.

**Session hygiene.**
- Do not queue open questions across turns — resolve them, or fold them into
  the research wave and close them when it returns.
- End each phase with a one-paragraph plain-language status the user can
  redirect.
