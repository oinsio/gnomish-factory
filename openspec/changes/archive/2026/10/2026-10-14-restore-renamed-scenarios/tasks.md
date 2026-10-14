## 1. Delta

- [ ] 1.1 Three `ADDED` blocks copied from `openspec/specs/` with one scenario title changed each (FR1)
- [ ] 1.2 `openspec validate restore-renamed-scenarios --strict` passes (FR1)

## 2. Markers

- [ ] 2.1 Rename markers naming `Interactive client with nothing declared passes the pin` and
  `The baseref leaf stays empty of dependencies`; add the `Returned-fact suite passes on both
  adapters` marker to the contract specs, keeping the lease-maintenance one (FR2)

## 3. Pending change

- [ ] 3.1 In `own-git-invocation-policy`, rename the `The baseref leaf stays empty of dependencies`
  scenario in its MODIFIED block; `openspec validate own-git-invocation-policy --strict` and
  `scripts/check-spec-contract.sh --changes` pass (FR3)

## 4. Rehearsal

- [ ] 4.1 In a scratch worktree archive both changes in order, validate specs strictly, `diff` the
  three spec files against the originals: exactly three titles differ (NFR-R1)

## 5. Archive

- [ ] 5.1 Archive `retire-misnamed-scenarios` then this change, same session, path per
  `archive-path.md`; `openspec validate --specs --strict` and `./gradlew checkSpecContract` green
  (M1, M2)
