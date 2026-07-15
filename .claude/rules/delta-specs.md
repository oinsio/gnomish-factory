---
paths:
  - "openspec/changes/**/specs/**"
  - "openspec/specs/**"
---

# Rule: capability spec format

Delta specs describe what a change adds to or modifies in a capability. They live in `openspec/changes/<change>/specs/<capability>/spec.md` during development and merge into `openspec/specs/<capability>/spec.md` after archive.

## Primary format: Requirement / Scenario (OpenSpec `spec-driven`)

The default format is the one the OpenSpec tooling generates and validates — requirements grouped under ADDED / MODIFIED / REMOVED headers, each with scenarios:

```markdown
## ADDED Requirements

### Requirement: <capability behavior, one sentence with SHALL>
Normative description of the requirement.
<!-- implements FR1 of <change-name> -->

#### Scenario: <observable case>
- **WHEN** <condition>
- **THEN** <expected outcome>
- **AND** <additional outcome>
```

## Domain-model format: Entity / Use case

When a spec describes the domain model itself (entities, fields, invariants), use ADDED / MODIFIED / REMOVED markers with YAML-like notation:

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

- Every requirement, invariant, and use case MUST carry an `implements FR-X of <change-name>` traceability link — `<!-- ... -->` in Requirement/Scenario specs, `# ...` in YAML-like blocks (see `traceability.md`)
- In the Entity format, use YAML-like notation for field definitions
- Keep specs focused: one capability per spec file
- Delta specs are temporary — they describe the change, not the full state
- After `/opsx:archive`, deltas merge into `openspec/specs/` — never edit archived specs directly
