# git-transfer — delta for own-git-transfer-argv

## Purpose

Governs every git transfer the factory performs — a fetch or a clone from any source — so that
each one changes exactly the refs it named, under a closed protocol allowlist, with validated
objects, unaffected by the operator's environment, and so that no transfer can be built anywhere
but in one owner.

## ADDED Requirements

### Requirement: One owner builds every transfer
Every factory git transfer (a fetch or a clone) SHALL be built by one owner from exactly two
inputs: a transfer source of a closed set of kinds — the `origin` remote, a task container
reached over the `ext::` transport, or a local seed path — and one refspec. The owner SHALL
produce the argument list and the environment as values and SHALL launch nothing; the medium
that runs it (the factory git runner, or the seed helper's script) is the caller's. Adding a
transfer source SHALL mean adding a kind, never a flag list at a call site.
<!-- implements FR1, FR2 of own-git-transfer-argv -->

#### Scenario: A harvest and a base refresh share one flag set
- **WHEN** the harvest fetch of a task branch from a container and the base refresh of a branch
  from origin are built
- **THEN** both carry the same common side-effect set and differ only in source, protocol
  allowlist, configuration isolation, and the refspec

#### Scenario: A new source kind needs no new flags
- **WHEN** a sandbox backend adds a transfer source (a VM or a remote runner)
- **THEN** it supplies the source's transport and protocol name, and the common set, validation,
  isolation, and the build gate apply to it without change

#### Scenario: The seed clone's branch stays a positional parameter
- **WHEN** the seed helper's script is rendered from the owner's clone value
- **THEN** the script is one constant string in which the branch position is the shell word
  `"$1"`, and no task branch name appears in the `sh -c` literal

### Requirement: Common deny-by-default side-effect set
Every transfer SHALL: follow no tags beyond the named refspec; write no `FETCH_HEAD`; recurse
into no submodule, by flag and by configuration, so that a populated submodule in the operator's
clone triggers no second fetch; prune nothing; run no automatic maintenance or garbage
collection in the clone; place an options terminator before the source and the refspec; apply,
for a named remote, an empty refmap so the clone's configured fetch refspecs move nothing; and
fetch full history, never shallow.
<!-- implements FR3, NFR-S1 of own-git-transfer-argv -->

#### Scenario: A tag inside the fetched history stays out
- **WHEN** the source holds a tag pointing at a commit inside the history the refspec names, and
  the clone holds no tag of that name
- **THEN** after the transfer the clone still holds no tag of that name

#### Scenario: A populated submodule is not fetched
- **WHEN** the operator's clone has a submodule initialized and the fetched commit references a
  newer submodule commit
- **THEN** the transfer contacts the submodule's remote not at all and updates no ref in the
  submodule

#### Scenario: FETCH_HEAD is untouched
- **WHEN** any transfer completes, delivered or refused
- **THEN** the clone's `FETCH_HEAD` file is byte-identical to what it was before, or still absent

#### Scenario: A refspec that looks like an option is a refspec
- **WHEN** the refspec's first character is `-`
- **THEN** git reads it as a refspec and refuses it as a bad ref name, never as an option

### Requirement: Closed per-source protocol allowlist
Every transfer SHALL run under a protocol allowlist the owner sets per source, which denies every
protocol not listed: `https`, `ssh`, and `file` for `origin`; exactly `ext` for a container;
exactly `file` for a seed path. The allowlist SHALL be the whole protocol policy: no transfer
carries a `protocol.*.allow` key or a `GIT_PROTOCOL_FROM_USER` value, because under the
allowlist every protocol is either always or never allowed and both would be inert.
<!-- implements FR4, NFR-S1 of own-git-transfer-argv -->

#### Scenario: A harvest cannot reach the network
- **WHEN** a harvest is built for a container
- **THEN** its allowlist names `ext` alone, and an `https` or `ssh` URL under the same argv is
  refused by git

#### Scenario: Origin cannot use the container transport
- **WHEN** a base refresh is built for origin
- **THEN** its allowlist omits `ext`, and an `ext::` URL — including one substituted by a URL
  rewrite in the operator's configuration — is refused by git

### Requirement: Received objects are validated
Every transfer SHALL validate the objects it receives, with exactly three legacy message ids
downgraded from error to ignore (`badTimezone`, `missingSpaceBeforeDate`, `zeroPaddedFilemode`).
A validation refusal SHALL be a named outcome and never an infrastructure failure: for a harvest
it is a boundary violation of the box; for a base refresh or a task-branch locate it is a
task-level park. The report SHALL name the message id and the object, as untrusted text.
<!-- implements FR5, NFR-R1, NFR-O2, NFR-P1 of own-git-transfer-argv -->

#### Scenario: A malformed .gitmodules from the box is refused
- **WHEN** a box commits a `.gitmodules` whose path or URL entry git's validation rejects, and
  the factory harvests the branch
- **THEN** the harvest is refused, the task ends as a boundary violation with a report naming
  the message id, and the operator clone's task branch ref is unchanged

#### Scenario: A legacy timezone does not refuse a base
- **WHEN** origin's history carries a commit with a malformed timezone and nothing else wrong
- **THEN** the base refresh delivers it

#### Scenario: Validation cost is local
- **WHEN** a transfer is validated
- **THEN** the source served exactly one upload-pack session for it, and the receiving
  repository has no shallow boundary

### Requirement: Per-source configuration isolation
A container or seed transfer SHALL read no global or system git configuration and no XDG
configuration home; the seed helper's own throwaway global file SHALL hold `safe.directory`
entries and nothing else. An `origin` transfer SHALL keep the operator's global configuration —
credential helpers and URL rewrites are the operator's — and the owner SHALL re-assert by
per-invocation configuration every key the common set depends on. Every transfer SHALL strip
from its environment any inherited per-process configuration (`GIT_CONFIG_PARAMETERS`,
`GIT_CONFIG_COUNT`) and any inherited object-directory, alternate-object, work-tree, or index
override.
<!-- implements FR6, NFR-S2, NFR-S3 of own-git-transfer-argv -->

#### Scenario: An operator config enabling recursion changes nothing
- **WHEN** the operator's global configuration sets submodule recursion and prune on, and a
  base refresh runs
- **THEN** no submodule is fetched and no ref is pruned

#### Scenario: A harvest sees no operator configuration at all
- **WHEN** the operator's global configuration rewrites URLs or names a credential helper, and
  a harvest runs
- **THEN** the harvest neither rewrites its `ext::` URL nor invokes any helper, and the box
  receives no credential

#### Scenario: Inherited environment cannot inject configuration
- **WHEN** the factory process was started with `GIT_CONFIG_COUNT` and matching
  `GIT_CONFIG_KEY_0`/`GIT_CONFIG_VALUE_0` in its environment
- **THEN** no transfer sees them

### Requirement: The runner refuses an unowned transfer
The factory git runner's untyped entry SHALL refuse the transfer subcommands — `fetch`, `clone`,
`pull`, `submodule update`, `remote update` — with an exception naming the owner; a transfer
SHALL enter the runner only as the owner's typed value. `pull` has no owner form and SHALL stay
refused on every path.
<!-- implements FR8 of own-git-transfer-argv -->

#### Scenario: A hand-built fetch is refused before it runs
- **WHEN** production or test code passes `fetch` (with or without leading `-c` pairs) to the
  runner's string entry
- **THEN** the runner throws before launching a process, and the exception names the owner

#### Scenario: An owned transfer runs
- **WHEN** the owner's value for any source kind is handed to the runner's typed entry
- **THEN** the runner executes it under the same bounded-network, stall-detection, and
  clone-mutation-lock rules as before

### Requirement: Transfer argv stays inside the owner
A build gate SHALL fail when a production source file outside the owner spells a transfer
subcommand as a git argument literal or inside a shell script literal, and SHALL assert that it
reached every production source root, so a new module cannot fall outside the scan.
<!-- implements FR9, M1 of own-git-transfer-argv -->

#### Scenario: A new fetch site fails the build
- **WHEN** a production class outside the owner adds `"fetch"` to a git argument list, or a
  script literal containing `git clone`
- **THEN** `./gradlew check` fails naming the file

#### Scenario: The scan proves its reach
- **WHEN** the gate runs
- **THEN** it asserts that every production source root in the build was scanned, and fails if
  one was not

### Requirement: Transfer identity on real git
For each source kind one spec on real git SHALL assert, on an adversarial fixture — a tag
pointing into the fetched history, a pre-existing tag of the same name at another commit, an
initialized submodule whose remote has advanced, and a global configuration enabling recursion
and prune — that the set of refs changed by the transfer is exactly the named destination, and
that `FETCH_HEAD` is untouched.
<!-- implements FR12, UX1, M2 of own-git-transfer-argv -->

#### Scenario: Ref diff equals the named destination
- **WHEN** a transfer for any source kind runs against the adversarial fixture
- **THEN** the difference between the clone's refs before and after is exactly the one
  destination the refspec named

### Requirement: Git version floor at startup
The factory SHALL read the installed git version once at startup, before any transfer, and
SHALL refuse to run below 2.45.1 — the release whose local-clone protections the seed clone
depends on — with a report naming the floor, the installed version, and the reason. A git that
cannot report its version SHALL be refused the same way. The check SHALL log one INFO line with
the detected version and the floor when it passes.
<!-- implements FR10, NFR-R2, NFR-O1, UX2 of own-git-transfer-argv -->

#### Scenario: Old git is refused before any work
- **WHEN** the installed git reports a version below the floor
- **THEN** the process exits with the precondition report and performs no transfer, no claim,
  and no tracker write

#### Scenario: Passing check is logged once
- **WHEN** the installed git is at or above the floor
- **THEN** one INFO line names the version and the floor, and the check does not run again in
  that process
