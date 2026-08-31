# git-task-persistence — delta for add-epic-decomposition

## ADDED Requirements

### Requirement: Plan and receipt are branch-contract shapes
The decomposition plan and the child-refs receipt SHALL be durable writes on
the task branch inside the factory-owned area, each landing as a single
commit whose durability point is the successful push. The branch states
"plan without receipt" and "plan with partial receipt" SHALL classify to
named shapes of the task-branch contract's shape set, enumerable by the
resume classifier, so any instance reading the branch can tell an
in-progress decomposition from a completed one without consulting the
tracker.
<!-- implements FR3, FR4 of add-epic-decomposition -->

#### Scenario: Branch alone reveals decomposition state
- **WHEN** an instance reads an epic's branch carrying a plan and receipts
  for two of four children
- **THEN** classification names the partial-receipt shape and identifies
  exactly which stable keys lack receipts

#### Scenario: Receipt lands as one commit per convergence pass
- **WHEN** recovery creates two missing children
- **THEN** their receipts land together in one pushed commit, not one push
  per child
