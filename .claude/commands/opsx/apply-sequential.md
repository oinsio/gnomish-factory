---
name: "OPSX: Apply Sequential"
description: Implement tasks from an OpenSpec change one-by-one in sub-agents, sequentially. No commits.
category: Workflow
tags: [workflow, artifacts, experimental]
---

Wrapper around `/opsx:apply` that enforces sequential sub-agent execution and no commits.

**How it works**: Invoke `/opsx:apply $ARGUMENTS` via the Skill tool, but with the following additional constraints injected into the execution:

## Execution constraints

1. **One task per sub-agent** — each pending task must be implemented in a separate foreground sub-agent (Agent tool). The sub-agent receives the full task context (proposal, specs, design, affected files) and implements that single task.

2. **Strictly sequential** — do NOT launch the next sub-agent until the current one finishes and returns its result. Never run tasks in parallel.

3. **No commits** — the sub-agent prompt MUST include: "Do NOT make any git commits." The user will commit manually.

4. **Mark progress immediately** — after each sub-agent completes successfully, mark the task as done in the tasks file (`- [ ]` → `- [x]`) and log a brief summary before moving to the next task.

5. **Pause on issues** — if a sub-agent reports a blocker, unclear requirement, or design issue, stop and ask the user before continuing.

6. **Single-owner tasks carry their consumer list** — for a task that wires or revises a mechanism named in the design's single-owner table (`.claude/rules/design-decisions.md`), the sub-agent prompt MUST include that table row verbatim and the definition of done from `.claude/rules/implementation.md`; the sub-agent's report MUST end with the old-way sweep (the grep run, every hit, what happened to each). A task whose consumers the design does not name is a design gap: stop and ask, do not let the sub-agent guess.
