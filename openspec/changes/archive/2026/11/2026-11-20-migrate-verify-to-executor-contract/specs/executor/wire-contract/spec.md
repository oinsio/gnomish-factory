# executor/wire-contract — delta for migrate-verify-to-executor-contract

## Purpose

The one request/result contract every executor of the factory speaks — built-in or external,
in any role — including its status taxonomy, answer kinds, describe handshake and the rules
that let the contract evolve without breaking executors built against an older version.

## ADDED Requirements

### Requirement: A describe may pin the check's id
An executor's describe MAY declare `checkIdIsPinnedPath` (boolean, default `false`): when
true, the engine's pin-check guard SHALL add the check's `checkId` — a repository-relative
path — to the check's pin set before the executor's first `start`. The field is static per
executor; no per-check pin hook exists on the contract or the mirror interface.
**Implements**: FR16 of migrate-verify-to-executor-contract
**Notes**: Layered after `define-executor-contract`; design D17.

#### Scenario: Declared flag pins the check id
- **WHEN** a check names an executor whose describe declares `checkIdIsPinnedPath: true` and the manifest declares no pin paths
- **THEN** the pin-check compares the file at `checkId` against the base branch before the first `start`

#### Scenario: Absent flag pins nothing of its own
- **WHEN** an executor's describe omits the field
- **THEN** the pin set is exactly the manifest's `pinPaths`, and an older describe without the field validates unchanged

### Requirement: A pending key stays pollable until its run ends
An executor whose describe declares `poll` SHALL answer a `poll` of every key it issued for a
run, from its `start` or from an earlier `poll`, until that run ends; a superseded key SHALL
reach the same run. A `poll` of a key whose run has ended SHALL return that run's terminal status
and answer again, unchanged, and SHALL NOT start the run anew.
**Implements**: FR11, NFR-R4 of migrate-verify-to-executor-contract
**Notes**: Layered after `define-executor-contract`, which adds this capability and archives first; the two requirements modified below are its text (design D14).
**Notes**: This is what lets a factory killed after a key rotation or after a terminal poll resume without a second `start` (design D7, D11).
**Notes**: A `poll` may name a working copy other than its `start`'s — a fresh box at the attempt commit for `verdict` and `decision`, the pending round commit for `transform` (design D11 as amended 2026-11-14) — so what a poll of the key needs lives outside the working copy: in the service the executor drives, in its own state, or in the key.

#### Scenario: A poll from another working copy reaches the run
- **WHEN** a `start` returned `pending` and the factory polls its key with a request naming another working copy, holding the content the run's role prescribes
- **THEN** the executor answers for the same run, `pending` or terminal, and does not refuse the key as unknown

#### Scenario: Re-poll of an ended run returns its result again
- **WHEN** a `poll` of a key returned a terminal status and the factory polls the same key again
- **THEN** the executor returns the same terminal status and answer, and no new run is started

#### Scenario: Superseded key still reaches its run
- **WHEN** a `poll` returned `pending` with a new key and the factory polls the previous key
- **THEN** the executor answers for the same run, `pending` or terminal, and does not refuse the key

#### Scenario: An executor that forgets an ended run is non-conforming
- **WHEN** a candidate refuses, or answers differently, a re-poll of a key whose run ended
- **THEN** the conformance kit's re-poll case fails naming the key, and the engine reads the refusal as `failed/infrastructure` naming the key, never as a new `start`

### Requirement: A recorded key is adopted only over an unchanged working copy
An executor that adopts a key from the task's pending record on resume SHALL accept its working
copy's view as the adopted run's before-view only when `HEAD` is the commit the factory put that
working copy on and the only other difference is the factory's own `state.json` lag; any other
view, or none, SHALL make the poll `failed/infrastructure` naming the key and the changed paths or
`HEAD`, with no process spawned.
**Implements**: FR3, FR11 of migrate-verify-to-executor-contract
**Notes**: The commit the factory put the working copy on is, for a fresh box, the attempt commit it was materialized at, otherwise the round's last registered commit; equality decides, never membership of the round's chain.
**Notes**: The expected `HEAD` is handed to the executor by its binding, never read from the working copy; a round with no registered commit adopts nothing.
**Notes**: The lag is `.gnomish-task/state.json` with the index's and the working tree's copies equal to each other and to the `state.json` of one of the round's registered commits — the first-parent chain from its last registered commit down to its snapshot, read on the factory's side by the walk the resume already makes (design D14, D21).
**Notes**: A host resume may reuse the task worktree a killed check program wrote into, and a same-box resume the box (design D14).
**Notes**: The baseline is a durable identity, the commit the factory put the working copy on, judged by the same owner as the factory's move during a run (design D14 as amended 2026-11-13, D21 "The lag the write leaves behind"; crash-consistency items 13 and 14), never a view re-taken on each poll.

#### Scenario: Adoption over a changed working copy is refused
- **WHEN** a resuming instance is handed a recorded key and its working copy holds a change against `HEAD` other than the factory's own `state.json` lag
- **THEN** the result is `failed/infrastructure` naming the key and the changed paths, no process is spawned, and the check resolves as cannot-verify

#### Scenario: A resume over the pending commit's lag adopts the key
- **WHEN** a resuming instance on the clone or in the box where the run was started is handed a recorded key, its working copy's `HEAD` is the round's registered pending commit, its index and working tree keep the `state.json` of an earlier registered commit of the round, and nothing else changed
- **THEN** the key is adopted, the program is spawned with `poll` and that key, and the answer that ends the run is judged against the view taken at the adoption

#### Scenario: Adoption away from the expected commit is refused
- **WHEN** a resuming instance is handed a recorded key and its working copy's `HEAD` is not the commit its binding expects
- **THEN** the result is `failed/infrastructure` naming the key and `HEAD`, and no process is spawned

#### Scenario: HEAD on an earlier commit of the round's chain is refused
- **WHEN** a resuming instance on the clone where the run was started is handed a recorded key, the round's last registered commit is a pending commit, and its working copy's `HEAD` is the round's snapshot or an earlier pending commit of the round
- **THEN** the result is `failed/infrastructure` naming the key and `HEAD`, and no process is spawned

### Requirement: The factory's own pending commit is not a change of the run
When the working copy's `HEAD` moved while a `verdict` or `decision` run was pending, the
working-copy check SHALL count the move as the factory's only when `HEAD` after the run names the
round's last registered commit, and SHALL count the path `.gnomish-task/state.json` as unchanged
in the index and the working tree only when both copies still equal its content at the view taken
before the run. Every other difference, any commit of the run included, SHALL refuse the run's
answer as before.
**Implements**: FR20 of migrate-verify-to-executor-contract; FR11, NFR-S4 of define-executor-contract
**Notes**: A pending commit moves `HEAD` and leaves the working copy's `state.json` behind it (design D21, "The lag the write leaves behind").
**Notes**: The move is judged by identity, never by the content between the two commits (crash-consistency item 13): under this rule any commit of the run is refused, an empty one or an edit-and-revert pair included; a round with no registered snapshot has no factory move, so any move of `HEAD` is refused.

#### Scenario: A pending commit does not refuse the run's answer
- **WHEN** a `program` verdict check answers `pending`, the factory lands its pending commit, and the run ends having changed nothing
- **THEN** the answer is judged, not refused

#### Scenario: A run's own change is still refused across a pending commit
- **WHEN** a `program` verdict check answers `pending`, the factory lands its pending commit, and the run edits `.gnomish-task/state.json` or any other path, or commits, before it ends
- **THEN** the answer is refused as `failed/infrastructure` naming the changed path, or `HEAD` for a commit

#### Scenario: An empty commit of the run is refused
- **WHEN** a `program` verdict check commits with no change of the tree (an empty commit, or an edit and its revert) during its run
- **THEN** the answer is refused as `failed/infrastructure` naming `HEAD`, whether or not the factory landed a pending commit during the run

### Requirement: A pending result may carry a wait hint
A `pending` result MAY carry `pollAfterMillis`, an integer from 0 to 86 400 000. The engine SHALL
NOT poll the key sooner than the larger of the check's interval and the hint after reading the
answer, and SHALL NOT extend the check's timeout for it. The hint or `pendingState` on a status
other than `pending`, or a hint outside its range, SHALL read as `failed/infrastructure` naming
the field.
**Implements**: FR2, NFR-R2 of migrate-verify-to-executor-contract
**Notes**: The hint is relative and is not persisted; a resumed poll goes at once (design D19, D7).

#### Scenario: Wait hint delays the next poll
- **WHEN** a `pending` answer carries a `pollAfterMillis` larger than the check's interval
- **THEN** the next poll of that key is made no sooner than the hint
- **AND** a hint shorter than the interval leaves the interval in force

#### Scenario: Wait hint past the deadline ends the wait
- **WHEN** a `pending` answer carries a hint that reaches past the check's timeout
- **THEN** no further poll is made and the check resolves at the timeout from that answer

#### Scenario: Misplaced or out-of-range pending fields fail closed
- **WHEN** a result carries `pollAfterMillis: -1`, or a `completed` result carries `pendingState`
- **THEN** the result reads as `failed/infrastructure` naming the field

### Requirement: A pending state classifies the timeout
A `pending` result MAY carry `pendingState`, one of `running`, `unreported`, `unobserved`. The
engine SHALL resolve a timeout whose last pending answer was `unreported` or `unobserved` as
cannot-verify, whatever the check's declared timeout class; `running`, an absent field and an
unknown token SHALL leave the declared class in force.
**Implements**: FR2, FR4, FR9 of migrate-verify-to-executor-contract
**Notes**: `unreported` is a subject that never appeared on the polled commit; `unobserved` is a source that could not be read, such as a rate limit (design D19).

#### Scenario: Unreported subject at the timeout cannot verify
- **WHEN** a `quality`-class check times out and its last pending answer was `unreported` or `unobserved`
- **THEN** the check resolves as cannot-verify and no attempt is burned

#### Scenario: Unknown pending state keeps the declared class
- **WHEN** a `quality`-class check times out and its last pending answer carried an unknown `pendingState` token
- **THEN** the check fails as a quality failure naming the timeout, as with no state at all

### Requirement: A result carries details apart from its message
A SARIF result MAY carry its details as `properties.details`, a string; a value of another type
SHALL be ignored, never read as a malformed answer. A result of any status MAY carry `details`, a
string, beside `reason`; the engine SHALL read it for a `failed` or `pending` result and ignore it
on `completed`, whose details travel on its SARIF results. The engine SHALL keep details apart from the message and the reason wherever it records
them.
**Implements**: FR5, FR6 of migrate-verify-to-executor-contract
**Notes**: Design D19; a request's feedback findings already carry `details`.

#### Scenario: SARIF details reach the finding's details
- **WHEN** a verdict answer's `error` result carries `properties.details`
- **THEN** the recorded finding carries that text as its details and its message unchanged

#### Scenario: A third-party details property of another type is ignored
- **WHEN** a SARIF result's `properties.details` is an object
- **THEN** the answer reads as a verdict and the finding carries no details

### Requirement: A verdict answer carries its run URL on the SARIF run
A SARIF run of a verdict answer MAY carry the URL of the platform run that produced it as
`properties.runUrl`, a string. The engine SHALL take the first run, in the order written, whose
`runUrl` is a non-blank string, and SHALL record it with the check's verdict when the check
passes; a blank value or a value of another type SHALL be ignored, never read as a malformed
answer.
**Implements**: FR6 of migrate-verify-to-executor-contract; NFR-O2 of add-sandbox-core
**Notes**: Design D23; the `github-actions` built-in writes its workflow run's page and the `http` built-in its target URL, and a failing check's links travel in its findings' details.

#### Scenario: A completed answer carries its run URL
- **WHEN** a verdict answer's run carries `properties.runUrl` and none of its results reaches the check's threshold
- **THEN** the check passes and the recorded check result carries that URL

#### Scenario: The first run's URL is recorded
- **WHEN** a passing verdict answer has two runs that each carry `properties.runUrl`
- **THEN** the recorded check result carries the URL of the run written first

#### Scenario: A malformed run URL is ignored
- **WHEN** a SARIF run's `properties.runUrl` is an object
- **THEN** the answer reads as a verdict and the pass carries no run URL

## MODIFIED Requirements

### Requirement: Poll of a program is refused without its preconditions
A poll of a program whose describe does not declare `poll`, or with a key the run neither
received for that check from a `start` or `poll` of the program nor adopted from the task's
pending record on resume, SHALL be `failed/infrastructure` naming the reason, with no process
spawned. A key the run received or adopted SHALL be pollable for that check from any binding of
the program in the run.
**Implements**: FR23 of define-executor-contract; FR3, FR11 of migrate-verify-to-executor-contract
**Notes**: Adoption happens on the resume path only, for a key the pending record names, never from the request on the wire; the table of pending runs is the run's, shared by every binding of the program's registry entry and keyed per check (design D14 as amended 2026-11-13).

#### Scenario: Poll of a program without the capability
- **WHEN** a poll is asked of a program whose describe lists no `poll` capability
- **THEN** the result is `failed/infrastructure` naming the undeclared capability and no process is spawned

#### Scenario: Poll with a key this run did not issue
- **WHEN** a poll carries a key that no `start` or `poll` of the program in this run returned for the check and that the run did not adopt from the task's pending record
- **THEN** the result is `failed/infrastructure` naming the key as unknown and no process is spawned

#### Scenario: A key returned to an earlier binding is polled
- **WHEN** a check's `start` ran on one binding of a `program` executor declaring `poll` and returned `pending`, and the check's next poll reaches a new binding of the same program in the same run
- **THEN** the program is spawned with `poll` and that key, nothing is adopted, and the answer that ends the run is judged against the view taken before its `start`

#### Scenario: A key issued for one check is unknown to another
- **WHEN** two checks of one stage use the same `program` executor and a poll for the second check carries a key returned only for the first
- **THEN** the result is `failed/infrastructure` naming the key as unknown and no process is spawned

#### Scenario: Recorded key is polled by a resuming instance
- **WHEN** an instance that did not start the run resumes a check whose pending record names a key of a `program` executor declaring `poll`
- **THEN** the program is spawned with `poll` and that key, and no `start` is called

### Requirement: A pending judge is judged when it ends
The working-copy check of a `verdict` or `decision` run SHALL be judged at the poll that ends a
pending run, against the view taken before its `start`, whichever binding of the program in the
same run took it, or, for a key adopted from the pending record on resume, against the view taken
of the resuming instance's working copy when the key was adopted; a `pending` answer itself SHALL
not be judged.
**Implements**: FR23, FR11 of define-executor-contract; FR3 of migrate-verify-to-executor-contract
**Notes**: In a fresh box (design D11 as amended 2026-11-14) each request runs in its own clean box at the attempt commit, so the files a `start` that returned `pending` wrote are disposed with its box and are not judged (design D14); the terminal poll's box is judged against the before-view of a clean copy of the same commit, and the scenarios below hold of the working copy the request names.

#### Scenario: Pending judge is judged when it ends
- **WHEN** a `verdict` run returned `pending`, a file of the working copy changed after its `start`, and its poll returns `completed`
- **THEN** the verdict is discarded and the check resolves as cannot-verify naming the path

#### Scenario: Adopted key is judged against the view taken at adoption
- **WHEN** a resuming instance adopted a recorded key, a file of its working copy changed after the adoption, and the poll returns `completed`
- **THEN** the verdict is discarded and the check resolves as cannot-verify naming the path
