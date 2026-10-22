# Verifying

Group 5 of [`../use-cases.md`](../use-cases.md): the events between "a round has ended" and
"the attempt has a verdict": a check comes back red, an external check stays silent past its
timeout, a judge returns its verdict. The voices are the pipeline author, the factory
operator, the reviewer, the security owner, the judge and the gnome; the stories follow the
form and rules of the index. What the factory does with a verdict it cannot obtain, or with
the last permitted attempt, is group 6; the law sealed at the base commit is `UC-SEAL-THE-LAW`
in group 4.

## Pipeline author (`SH-PIPELINE-AUTHOR`)

### UC-ORDER-THE-CHECKS — Say what is verified, in which order
**When** I write a stage's verification,
**I want** its checks run in the order I listed, the first non-pass ending the attempt, and
the judge reached only after every cheaper check has passed,
**so that** a red test costs no model call and a verdict always means every check before it
passed.

### UC-REPORT-WITHOUT-FAILING — Have a check that reports and never fails
**When** I add a check whose findings are advice rather than a verdict,
**I want** its findings to reach the tracker and the attempt record while burning no attempt
and failing no stage,
**so that** a lint-grade observation never costs a round.

### UC-PROTECT-THE-EVIDENCE — Catch a round that edits what grades it
**When** a round touches the tests, thresholds, build scripts or snapshots my checks rely on,
**I want** the attempt failed by a deterministic check before any judge runs, unless the
stage declares that it may touch them,
**so that** a passing check means the work passed, not that the bar moved.

### UC-GRADE-BY-WRITTEN-CRITERIA — Have the judge grade only what I wrote
**When** a judge check runs,
**I want** the verdict graded against the criteria sealed in the law, read only from its
structured fields, and decided by the majority of the votes I asked for,
**so that** a criterion means the same on every attempt and no vote is undefined.

## Factory operator (`SH-FACTORY-OPERATOR`)

### UC-BURN-ATTEMPTS-ONLY-FOR-QUALITY — Pay attempts only for red work
**When** a check cannot reach a verdict, because a service is down, a binary is missing, a
judge reply is no verdict, or the attempt commit never reached origin,
**I want** no attempt burned and the task parked with a report naming the check and why,
**so that** an outage never exhausts a task and a flaky service never passes for a failure
of the work.

### UC-BOUND-THE-WAIT-FOR-CI — Decide what a silent check means
**When** an external check has not answered by its timeout, or a command check has not exited,
**I want** the wait bounded by what I declared per check, and the silence charged to the work
or to the service by a class I chose,
**so that** a stuck check never holds a slot overnight and the wrong party is never charged.

## Reviewer (`SH-REVIEWER`)

### UC-READ-THE-VERDICTS — Read every verdict with its evidence
**When** I open a task, during the work or after it,
**I want** each attempt's check results, pass or fail, with their findings and the run link of
a passing external check, recorded on the branch and rendered by the status report,
**so that** I can tell what was verified without the factory's logs or the CI site.

## Security owner (`SH-SECURITY-OWNER`)

### UC-FINDINGS-ARE-DATA — Keep findings from steering the factory
**When** a check's output, a judge's reply or an external finding carries text,
**I want** the factory to branch only on structured verdict fields, the text carried as
inert data, stripped of control sequences, capped, and published inside a fence that escapes
mentions,
**so that** a deceived model or a hostile test output can neither pass a stage nor ping the
team.

### UC-JUDGE-ON-CLEAN-GROUND — Have the judge grade the commit, not the gnome's box
**When** a judge vote runs,
**I want** it to run in a fresh environment built from the attempt commit, never in the
environment the round left behind,
**so that** nothing a round planted outside the branch can grade itself.

### UC-PIN-THE-EXTERNAL-CHECK — Refuse a verdict whose definition the gnome rewrote
**When** the attempt commit changes the files that define an external check,
**I want** the check failed with the difference as findings before the service is asked,
**so that** a green run on a rewritten workflow never counts as a verdict.

### UC-BOUND-THE-HTTP-CHECK — Keep an http check from probing my network
**When** a stage declares a check that calls an http endpoint,
**I want** it to reach only destinations on an allowlist I own, with the scheme, the address
class and the response size bounded and only listed values interpolated,
**so that** a check cannot be turned into a probe or a tunnel.

## Judge (`AC-JUDGE`)

### UC-VOTE-ON-THE-WORK-ALONE — Receive the criteria and the work, nothing else
**When** I am asked for a vote,
**I want** the task goal, the decisions given, the acceptance criteria and the attempt's
artifacts as delimited data, with no earlier attempt's findings, and one place to put my
structured verdict,
**so that** my verdict is mine and not an echo of a previous one.

## Gnome (`AC-GNOME`)

### UC-LEARN-FROM-THE-RED-CHECK — Get the red check's findings in my next round
**When** a check fails my attempt,
**I want** its findings, each with the location it names, and those of every earlier attempt
of the stage, in my next briefing,
**so that** I fix what was found instead of rediscovering it.

## Coverage

The second column is the dry run of the `**Serves**:` lines: the capability and the
requirement headings that would carry the key. The third names entries of
[`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-ORDER-THE-CHECKS` | `pipeline/stage-engine`: Ordered fail-fast verification; `pipeline/pipeline-config`: Stage model mirrors the stage contract and all verify-check types | none |
| `UC-REPORT-WITHOUT-FAILING` | nothing | `GP-EVERY-FINDING-BLOCKS` |
| `UC-PROTECT-THE-EVIDENCE` | nothing | `GP-VERIFY-RUNS-GNOME-TREE` |
| `UC-GRADE-BY-WRITTEN-CRITERIA` | `executor/agent-executor`: CLI judge vote; `pipeline/stage-engine`: Judge majority voting; `verification/verification-hardening`: Control flow depends only on verdicts | none |
| `UC-BURN-ATTEMPTS-ONLY-FOR-QUALITY` | `pipeline/stage-engine`: Failure classification, External check poll loop; `verification/github-external-check`: Infrastructure failures burn no attempt, Misconfiguration fails fast without burning an attempt; `verification/verification-hardening`: Control flow depends only on verdicts | none |
| `UC-BOUND-THE-WAIT-FOR-CI` | `pipeline/stage-engine`: External check poll loop; `pipeline/pipeline-config`: External check declarations carry a timeout class; `verification/check-provider-model`: Command checks are bounded | none |
| `UC-READ-THE-VERDICTS` | `pipeline/stage-engine`: Outcome and report model, Strict attempt persistence, External check poll loop; `observability/status-report`: Single report model behind every render, JSON contract v1, state-derived | none |
| `UC-FINDINGS-ARE-DATA` | `verification/verification-hardening`: Unified findings funnel, Control flow depends only on verdicts, Sanitized logs and fenced tracker publication, Findings volume is capped | none |
| `UC-JUDGE-ON-CLEAN-GROUND` | `verification/verification-hardening`: Sandboxed judge votes run in a fresh environment; `executor/agent-executor`: CLI judge vote | `GP-HOST-MODE-BEST-EFFORT` |
| `UC-PIN-THE-EXTERNAL-CHECK` | `verification/verification-hardening`: Pin-check guards external checks; `verification/github-external-check`: The adapter contributes its workflow file to the pin set; `pipeline/pipeline-config`: External check declarations carry pin paths | `GP-VERIFY-RUNS-GNOME-TREE` |
| `UC-BOUND-THE-HTTP-CHECK` | `verification/factory-egress-allowlist`: The http check is governed by an operator-owned egress allowlist, The http check enforces scheme, address-class, and resource limits, Interpolation into http requests is restricted to a whitelist | none |
| `UC-VOTE-ON-THE-WORK-ALONE` | `executor/agent-executor`: CLI judge vote; `verification/verification-hardening`: Judge input is delimited data | none |
| `UC-LEARN-FROM-THE-RED-CHECK` | `pipeline/stage-engine`: Failure classification; `executor/agent-executor`: Round prompt composition | none |
