## Audit Resolution: add-doctor-command

Source: temporary-docs/gnomish/github-oinsio-gnomish-factory-112/review-code.md

### 1 — applied
- Recommendation: WARNING — eight delta scenarios have a test but no `// Scenario:` marker
- Re-verified: `openspec/changes/add-doctor-command/specs/cli/operator-doctor/spec.md:19`, `:32`, `:37`, `:43`, `:60`, `:76`, `specs/git/git-transfer/spec.md:28`, `specs/operator/operator-configuration/spec.md:35` — the eight scenario headings exist; `grep -rn "Scenario: cli/operator-doctor / Every readiness"` (and the other seven titles) over the test trees found no marker; each named feature exercises its scenario (e.g. `LauncherHelperDoctorCheckSpec.groovy:29` has the `setsid` row and the `/bin/kill` row, the two scenarios' WHEN/THEN).
- Edit: marker comments only, titles copied verbatim from the delta headings, placed above the features the audit named:
  `bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/DoctorDispatchSpec.groovy:63`–`:64` (`cli/operator-doctor / Every readiness check is reported`, `git/git-transfer / Doctor reports an old git`);
  `bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/doctor/GuardImageDoctorCheckSpec.groovy:30` and `bootstrap/src/test/groovy/com/github/oinsio/gnomish/DoctorBootSpec.groovy` above "FR1: a leftover of the pre-registry layout only warns" (`cli/operator-doctor / Absent guard image and old layout leftovers only warn`);
  `DoctorBootSpec.groovy` above "FR7: a configuration with a violation still boots doctor…" (`operator/operator-configuration / Doctor lists violations instead of stopping`);
  `bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/doctor/LauncherHelperDoctorCheckSpec.groovy:27`–`:28` (`Missing launcher helper`, `Missing group sweep signaller`);
  `bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/doctor/HooksPathDoctorCheckSpec.groovy:34` (`Leftover hooks path of an earlier version`);
  `bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/doctor/TrackerReadProbeGithubSpec.groovy:118` (`Offline by default`).
  Traceability, not behaviour: no red step. `scripts/check-spec-contract.sh` exits 0 with the markers (no dangling marker, so every title resolves through the delta).

### 2 — rejected
- Recommendation: SUGGESTION — a 401 from the tracker probe names no cause, so the fix line points at nothing
- Re-verified: `adapters/github/src/main/java/com/github/oinsio/gnomish/adapter/tracker/github/GithubLabelProvisioner.java:129`–`:136` — `cause` has arms for 404, 403 and 422 and `default -> "unexpected HTTP " + statusCode`, so a 401 reads "unexpected HTTP 401", as reported.
- Evidence: `.claude/rules/review-recommendations.md`, "Resolving recommendations": "A **SUGGESTION** is applied only when the edit is trivially safe — local, changing no behaviour, scope, requirement, task or public surface. Otherwise reject it." The fix changes behaviour: `cause` is the one classification shared by the listing and the creation ("The one classification of a refused status, shared by the listing and the creation", `GithubLabelProvisioner.java:128`), so it rewords the startup refusal of every `take`/`serve`/`run` on a 401, not only the doctor line — the audit's own Fix risk says so ("the startup refusal message for a 401 changes with it"), and it edits three pinned rows in two modules. Startup has the same wording today by design (G2, `proposal.md:69`: doctor never disagrees with startup). A wording improvement for both paths is a separate, behaviour-changing edit for a change that owns the GitHub refusal text, not a trivially safe suggestion.

### 3 — rejected
- Recommendation: SUGGESTION — the guard-image check reports an unreachable runtime as an absent image with a `docker pull` fix
- Re-verified: `bootstrap/src/main/java/com/github/oinsio/gnomish/app/doctor/GuardImageDoctorCheck.java:45`–`:56` — `present` catches `RuntimeException` and returns `WARN "<image> is not present locally (<cause words>); a box pulls it on first use"` with fix `docker pull <image>`; the cause words do quote the unreachable runtime.
- Evidence: same rule as 2 — the fix changes the check's output (a new detail and a different fix for one exception type) and needs a new spec feature, so it is not "local, changing no behaviour". The audit's Impact concedes no wrong verdict: "with Docker down, the run already carries `fail  Docker runtime: …` from `DockerDoctorCheck`, so the verdict is not wrong overall", and the line's own words already name the unreachable runtime — the Impact is clarity only, which `review-recommendations.md` ("Worth fixing", 2) does not count: "'Clearer', 'more consistent', 'more robust' with no failure behind it is no impact."

### 4 — rejected
- Recommendation: SUGGESTION — `TrackerWiring.resolveRef` spells the unknown-tracker-type refusal a second time beside the method whose javadoc claims to be the single lookup
- Re-verified: `application/src/main/java/com/github/oinsio/gnomish/app/TrackerWiring.java:102`–`:109` (`resolveFactory`, the single lookup) and `:176`–`:186` (`resolveRef`, its own `registry.get` and `UsageException`) — the duplication exists. `git diff origin/main...HEAD -- application/.../TrackerWiring.java` touches neither `resolveRef` nor `resolveFactory`; the only new line using the lookup is the `probeRead` relay (`return resolveFactory(trackerConfig).probeRead(secrets, trackerConfig);`), which does go through the single lookup.
- Evidence: `review-recommendations.md`, "Worth fixing", 2 (Impact): the audit states "No behaviour is wrong today"; the remaining impact is a possible future drift — "more consistent" with no failure behind it. The duplicated code predates this change and is not part of its diff, so editing it is a restyle of working code outside the change ("restyles working code" on the drop list).

### Other edits
None. (`./gradlew spotlessApply` made no further changes.)

### Verification
- `scripts/check-spec-contract.sh`: exit 0.
- `./gradlew check`: BUILD SUCCESSFUL in 53m 23s (1001 actionable tasks: 58 executed, 943 up-to-date), after `./gradlew spotlessApply`.
