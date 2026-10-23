# Gaps: decisions

Entries of [`../gaps.md`](../gaps.md) about forks, arbiters, answers and their record. The
form and the vocabulary are in the index.

### GP-NO-ARBITER-TIER — Every fork the gnome raises parks the task for a human
**Breaks:** `UC-SETTLE-ROUTINE-FORKS-BY-RULE`, `UC-VETO-AN-ARBITER-DECISION`, `UC-CHOOSE-AMONG-GIVEN-OPTIONS`
**Where today:** `pipeline/stage-engine`: Gnome-initiated decision escalation (every decision request escalates to the tracker); `executor/agent-executor`: Decision-file protocol (free-text options); no decision rules in the law
**Kind:** `missing`
**Resolution:** `planned: add-decision-arbiter` (a per-stage arbiter under law-frozen decision rules, a closed verdict among the gnome's options, a per-stage cap, fallback to the human with the analysis, advisory decisions a human can veto)

### GP-MALFORMED-REQUEST-PARKS — A request that is not a question is parked for the human
**Breaks:** `UC-DECIDE-FROM-THE-REPORT`
**Where today:** `executor/agent-executor`: Decision-file protocol (an unparseable request becomes a question whose text is the raw content, with no options)
**Kind:** `missing`
**Resolution:** `planned: add-decision-arbiter` (a structured request with enumerated options; a malformed one is quality feedback to the gnome, never a park)

### GP-DECISION-AUTHOR-OPTIONAL — A decision record need not say who gave it or for what scope
**Breaks:** `UC-READ-EVERY-DECISION`
**Where today:** `git/git-task-persistence`: State directory with one writer per file (`decisions[] {text, author?, stage?, at?}`); `pipeline/stage-engine`: Human decisions as pass-through context (optional stage, author and time); four append sites, none of which must fill them
**Kind:** `missing`
**Resolution:** `planned: add-decision-arbiter` (author and scope on every record, one append owner)
