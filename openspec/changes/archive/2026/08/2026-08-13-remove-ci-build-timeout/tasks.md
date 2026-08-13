## 1. Workflow change

- [x] 1.1 In `.github/workflows/ci.yml`, remove `timeout-minutes: 30` from the `build` job, leaving no per-job cap (FR1, design D1/D3)
- [x] 1.2 Confirm `codeql.yml` and `gitleaks.yml` still declare `timeout-minutes: 30` and `osv-scan.yml` is untouched (FR2, design D2)

## 2. Spec alignment

- [x] 2.1 Reconcile the `quality-gates` Continuous-integration delta in the active `split-into-modules` change with the new wording, so whichever change archives second does not restore the blanket 30-minute rule (design D3 risk)

## 3. Verification

- [x] 3.1 `grep -c timeout-minutes .github/workflows/ci.yml` returns 0; `codeql.yml` and `gitleaks.yml` each return 1 (M2)
- [x] 3.2 `openspec validate remove-ci-build-timeout --strict` passes
