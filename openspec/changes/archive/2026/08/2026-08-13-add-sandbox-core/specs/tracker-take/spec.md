# tracker-take (delta)

## MODIFIED Requirements

### Requirement: Revocation detected at round boundaries
After every durably persisted round the factory SHALL verify the task is still
ours and alive in one tracker query — not closed, claim intact, state not changed
by a human. On revocation, it SHALL salvage uncommitted work through the bound
task environment — sandboxed: a salvage commit via `exec` inside the environment,
then harvest; host: a salvage commit in the worktree — push best-effort
factory-side per the push safety rules (harvest precedes any push), post a
structural "work stopped" note, release the claim, leave the tracker state
untouched, and keep the branch and the working copy per the keep semantics of
git-task-persistence — host: worktree kept; sandboxed: container stopped, volume
and network retained. Revocation SHALL surface as a runner-level result, not as
an engine `TaskOutcome`.
<!-- implements FR15 of add-tracker-port -->
<!-- implements FR5, FR6 of add-sandbox-core -->

#### Scenario: Issue closed under a working gnome
- **WHEN** a human closes the issue while a round is executing
- **THEN** at the next round boundary the run stops, salvages and pushes the work,
  posts the stop note, releases the claim, and reports the revocation result

#### Scenario: Revoked sandboxed task keeps a stopped environment
- **WHEN** revocation is detected while the task runs in container mode
- **THEN** leftovers are salvage-committed inside the environment and harvested,
  the push runs factory-side after the harvest, and the environment is kept
  stopped with volume and network retained
