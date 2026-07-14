---
paths:
  - "openspec/changes/**/specs/**"
  - "openspec/specs/**"
---

# Rule: domain spec format

Delta specs describe what changes in the domain model. They live in `openspec/changes/<change>/specs/<capability>/spec.md` during development and merge into `openspec/specs/<capability>/spec.md` after archive.

## Delta format

Use ADDED / MODIFIED / REMOVED markers:

```markdown
## Entity: Task

### MODIFIED
fields:
  + claimed_by: { type: FactoryInstanceId, optional: true }

invariants:
  + a task is claimed by at most one factory instance  # implements FR1 of add-task-claiming
```

## Use case format

```markdown
## Use case: ClaimTask (NEW)

Inputs:
- taskId: TaskId
- instanceId: FactoryInstanceId

Effects:
- task assigned to instanceId in the tracker  # FR1
- claim re-verified after the claim-confirmation delay  # FR2

Errors:
- TaskAlreadyClaimedError — another instance holds the claim  # FR1
```

## Rules

- Every invariant and use case MUST have `# implements FR-X of <change-name>` comment
- Use YAML-like notation for field definitions
- Keep specs focused: one capability per spec file
- Delta specs are temporary — they describe the change, not the full state
- After `/opsx:archive`, deltas merge into `openspec/specs/` — never edit archived specs directly
