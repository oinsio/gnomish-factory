## 1. Bootstrap verification metadata

- [ ] 1.1 Generate `gradle/verification-metadata.xml` with sha256 checksums for
  all resolvable configurations, including plugins and `build-logic`
  (`./gradlew --write-verification-metadata sha256 help`) (FR1, D1).
- [ ] 1.2 Add the trusted-artifact entries for `sources` / `javadoc`
  classifiers (D4) and re-run a full `check` + an IntelliJ sync to confirm
  UX2.
- [ ] 1.3 Verify idempotence: run the regeneration command twice with no
  dependency change; the second run produces an empty diff (NFR-R1, M3).

## 2. CI enforcement

- [ ] 2.1 Add the official `gradle/actions/wrapper-validation` step to
  `ci.yml` (FR5, D3).
- [ ] 2.2 Audit all workflows: no flag or property disables dependency
  verification anywhere in CI (FR5, D5).

## 3. Fail-closed verification (M1 tamper test)

- [ ] 3.1 Tamper test: corrupt one cached artifact (or add an unlisted
  dependency) and confirm the build fails naming the artifact, expected vs
  actual, before any code from it executes (FR2, NFR-O1, M1). Record the
  procedure in the docs task 4.1.
- [ ] 3.2 Confirm the failure surface points to the fix: the documented
  regeneration command is reachable from the failure docs (FR2, UX1).

## 4. Docs + update flow

- [ ] 4.1 Document, next to the Dependabot config reference: the regeneration
  command, the reviewer-run Dependabot flow (check out branch → regenerate →
  push metadata commit), the tamper-test procedure, and the threat model
  incl. the `open-adapter-binding-registry` NFR-S1 residual this closes
  (FR3, FR4, NFR-S1, D2).
- [ ] 4.2 Prove the flow on one real bump (a live Dependabot PR or a manual
  version bump run through the same steps) (M2).

## 5. Traceability + handoff

- [ ] 5.1 Verify every FR/NFR/UX of `add-dependency-verification` has an
  implementing entity (metadata file, CI step, docs, or recorded test) per
  `traceability.md`.
- [ ] 5.2 Recommend a Conventional Commits message referencing this change
  (the agent never commits).
