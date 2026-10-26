# Tasks

One human step, carried over from task 6.3 of `add-release-pipeline` (archived 2026-10-04) and
then task 6.3 of `fix-docker-exec-env-argv`, which this change relieves of it. No code, no specs.

## 1. First release

- [ ] 1.1 Prerequisites (FR1): `fix-docker-exec-env-argv` is merged and archived; the `main` CI
      run of the commit to tag is green. Verify: `gh run list --branch main --limit 1` shows
      success on that commit.
- [ ] 1.2 Rehearsal, optional (FR2): on a fork, push a throwaway `v0.0.1-rc.1`, watch the release
      workflow, then delete the tag and the release. Verify: the fork holds neither the tag nor the
      release afterwards; the origin repository was not touched.
- [ ] 1.3 Push `v0.1.0` on the green `main` commit (FR1, M1–M3): `git tag v0.1.0 <commit> &&
      git push origin v0.1.0`. Verify the release page holds the four assets and, per archive, a
      provenance and an SBOM attestation; `gh attestation verify gnomish-0.1.0.tar.gz --repo
      oinsio/gnomish-factory` passes; `gnomish --version` from the unpacked archive prints `0.1.0`.
      Attach the run link and the verify output to this task's report (NFR-O1).
- [ ] 1.4 If the run fails (NFR-R1, NG1): delete the tag and the release draft, open a
      `fix-release-...` change for the defect, and return to 1.3 after it is merged. Do not tag the
      same commit under another version.
