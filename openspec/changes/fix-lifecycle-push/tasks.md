# Tasks: fix-lifecycle-push

## 1. Shared push core (`adapter.git.remote`, design D2)

- [ ] 1.1 Spec + implement the origin reader in `adapter.git.remote`: presence check and URL read over the injected `GitProcessRunner`; absorb `OriginRemoteUrl` (move/rename, update its callers). FR1 groundwork, M3
- [ ] 1.2 Spec + implement the refspec push primitive (`push origin <branch>:<branch>`, never force, result surfaced to the caller); no policy, no logging of its own. FR1, NFR-S1
- [ ] 1.3 Spec + implement the remote-tip reader: `ls-remote` for the branch ref plus a local-ancestry answer, extracted from `RemoteAttemptDelivery.deliveredPerRemoteTip`. FR3/FR4 groundwork
- [ ] 1.4 Rebase `BestEffortPush`, `BranchPush`, and `RemoteAttemptDelivery` onto the core, deleting their inline `originConfigured` and push-command copies; their preconditions and WARN logging stay unchanged, existing specs stay green. M3

## 2. Tier 1 — lifecycle push decorators (design D1)

- [ ] 2.1 Spec first (port-fake, per design D6): decorator over `TaskRepository` pushes best-effort after `createTask`, `appendDecision`, and `recordOutcome`, exactly one push per call including `recordOutcome(Completed)`'s outcome+cleanup pair, push failure never propagates. FR1, FR2, NFR-R1
- [ ] 2.2 Implement the `TaskRepository` decorator over the shared core; WARN carries task, branch, and lifecycle event. FR1, NFR-O1
- [ ] 2.3 Spec + implement the `TaskLifecycleStore` decorator (adds `confirmTerminalWrite` delegation + push), sharing the push logic with 2.2 per design D1's port-shape note. FR1
- [ ] 2.4 Wire host mode: `GitTaskStore.taskRepository()` returns the decorated repository. FR1
- [ ] 2.5 Wire sandbox mode: the container support bundle's `taskRepository()` returns the decorated bare-object repository, reusing its existing push seam; delete the two caller-side pushes in the container termination path in this same task. FR1, FR6
- [ ] 2.6 Bare-origin integration spec (host + sandbox): task driven to `Completed` ends with origin tip == local tip, zero manual pushes; local-only run attempts no push and logs nothing. M1, UX1, UX3

## 3. Tier 2 — origin reconciliation at touchpoints (design D3)

- [ ] 3.1 Spec + implement the reconciliation check in `adapter.git.remote`: local tip taken as a parameter, one remote-refs read, best-effort catch-up push when origin is behind or missing the branch, never throws, WARN on failure, catch-up logged. FR3, NFR-R1, NFR-O1, NFR-C1
- [ ] 3.2 Wire the resume-start touchpoint (host and container resume bootstraps), each mode supplying its native local tip. FR3
- [ ] 3.3 Wire the terminal-boundary touchpoint. FR3
- [ ] 3.4 Resolve design Q1: trace whether any fresh-claim path bypasses resume bootstrap; wire or record the finding in this task's completion note. Q1
- [ ] 3.5 Integration spec: crash-shaped fixture (terminal commit local, origin behind) healed by a resume's touchpoint check; unreachable origin degrades to WARN without blocking. FR3, M1

## 4. Tier 3 — delivery fence before a park's tracker write (design D4)

- [ ] 4.1 Spec + implement the fence as a thin sibling of `RemoteAttemptDelivery` over the shared core: verify tip delivered → push → one bounded re-attempt → structured delivered/undelivered verdict; silent no-op without origin. FR4, NFR-R2
- [ ] 4.2 Wire the fence into the host park exit between `recordOutcome` and the terminal tracker write; on exhaustion the park proceeds and the park report gains a one-line origin-behind note. FR4, FR5, UX2
- [ ] 4.3 Integration spec: park with transient-then-successful push lands on origin before the tracker write observes it (bare-origin + tracker fake reading origin at write time); unreachable origin parks anyway with the note and WARN. M2, FR5, NFR-O1

## 5. Verification and docs

- [ ] 5.1 Grep gate as a spec or check: exactly one production construction site of each remote primitive — push command, origin-presence check, `ls-remote` tip read; no push in `bootstrap` termination paths. M3
- [ ] 5.2 Orphan sweep: confirm no class left without callers by the refactor (`OriginRemoteUrl` if fully absorbed, any helper obsoleted by 1.4/2.5); delete what the sweep finds — the build's unused-code check catches members, not whole classes. FR6, M3
- [ ] 5.3 PIT: run `:adapters:git:check` (and touched modules); per design D6 the decorators are expected to pass fully specced with no exemption, like `PushBestEffortAttemptPersistence`; no new unjustified exemptions
- [ ] 5.4 Update the operator guide: lifecycle pushes, the fence note in park reports, and the `push.default = matching` masking caveat. UX1, UX2
- [ ] 5.5 Glossary check: no new domain terms expected; confirm "fence"/"touchpoint" usage stays document-local or add entries if a second document needs them
- [ ] 5.6 Full `./gradlew check` green; recommend a commit message referencing fix-lifecycle-push
