# Tasks: fix-ci-build-timeout

## 1. Raise workflow timeouts

- [x] 1.1 In `.github/workflows/ci.yml`, change the build job `timeout-minutes` from `10` to `30` and update the trailing comment to state the 30-minute budget and its runaway-protection rationale (FR1, NFR-C1, NFR-O1, D1).
- [x] 1.2 In `.github/workflows/codeql.yml`, set the job `timeout-minutes` to `30` with a matching rationale comment (FR2, NFR-C1, NFR-O1, D2).
- [x] 1.3 In `.github/workflows/gitleaks.yml`, set the job `timeout-minutes` to `30` with a matching rationale comment (FR2, NFR-C1, NFR-O1, D2).

## 2. Verify scope

- [x] 2.1 Confirm `.github/workflows/osv-scan.yml` is left unchanged and its existing note explains why a per-job timeout cannot be set there (NG3, D3).
- [x] 2.2 Grep all workflow files: every `timeout-minutes` present equals `30`, and OSV is the only job without one (M2).
