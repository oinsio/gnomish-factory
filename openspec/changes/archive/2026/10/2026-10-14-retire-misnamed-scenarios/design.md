## Context

Driven by FR1 and NFR-R1 of the proposal. OpenSpec 1.14.1 (`dist/core/specs-apply.js`) offers no
way to rename a scenario; see the proposal's Why.

## Decisions

### D1: remove the requirement, re-add it in the next change

The only operation the tool accepts that makes a scenario title disappear is removing its whole
requirement. The partner change adds the requirement back under the same header, so pending
changes that name the header keep resolving.

### D2: the pair is archived back to back

Between the two archives `checkSpecContract` is red (markers point at scenarios that are gone).
That state is never committed: both archives and the marker edits land in one commit.

### D3: verify before archiving

A scratch worktree runs both archives and `diff`s the three spec files against the originals; only
then does the real tree archive. Archiving with `--skip-specs` or editing `openspec/specs/` by hand
is rejected (it is the path `tighten-spec-contract` D3 closed).
