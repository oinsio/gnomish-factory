# git/git-invocation-policy

## Purpose

Governs how every git process the factory starts is invoked. The factory states its own
policy on each command line and does not depend on the operator clone's shared configuration
file: the file is neither read as trusted policy nor written as storage.

## ADDED Requirements

### Requirement: Every factory git process carries one invocation policy
Every git process the factory starts — through the factory git runner or through the
git-object library — SHALL carry one invocation policy, built by one owner, as command-scope
configuration placed before the subcommand. The policy SHALL neutralize, by value: hooks
(the hook directory and every hook event), commit signing, tag signing, the filesystem monitor,
branch upstream auto-setup, automatic maintenance, automatic garbage collection, and replace refs.
**Implements**: FR1, FR2, FR9, NFR-S2 of own-git-invocation-policy
**Notes**: Hooks defined in the configuration (`hook.<name>.command`, git 2.54+) ignore `core.hooksPath`; only the event-level switch, documented in 2.56, disables them (design D3).

#### Scenario: A hook defined in the configuration does not run
- **WHEN** the clone's local configuration defines a hook by `hook.<name>.command` with
  `hook.<name>.event` set to `reference-transaction`, `post-checkout` and `post-index-change`,
  and the factory creates a task branch, adds its worktree, commits a round and updates a ref
- **THEN** the configured hook does not run, and every operation succeeds

#### Scenario: A planted local configuration runs nothing
- **WHEN** the clone's local configuration plants a hook directory with executable
  `pre-commit` and `reference-transaction` hooks, `commit.gpgSign=true` with a `gpg.program`,
  and a `core.fsmonitor` command, and the factory commits in a worktree, updates a ref, writes
  a commit through the git-object library, and adds a worktree
- **THEN** no planted program runs, and every operation succeeds

#### Scenario: Replace refs do not change what the factory reads
- **WHEN** the clone holds a `refs/replace/` entry substituting a commit on a task branch
- **THEN** the factory's reads of that branch see the original commit

#### Scenario: Factory git starts no background maintenance
- **WHEN** the factory writes a commit object and moves a ref in a clone whose loose-object
  count exceeds the automatic maintenance threshold
- **THEN** no maintenance or gc process is started by those commands

#### Scenario: Operator credentials still reach origin
- **WHEN** the operator's global configuration names a credential helper and the factory
  pushes to `origin`
- **THEN** the helper is consulted as before

#### Scenario: The policy sets no credential or transport key
- **WHEN** the invocation policy's configuration set is listed
- **THEN** it holds no credential helper, no SSH command and no URL rewrite; those keys stay
  ambient for the named remote

### Requirement: Inherited per-process configuration is stripped for every command
Every factory git process SHALL be started with inherited `GIT_CONFIG_PARAMETERS` and
`GIT_CONFIG_COUNT` removed from its environment, whatever the subcommand.
**Implements**: FR3 of own-git-invocation-policy

#### Scenario: An inherited configuration pair does not reach a commit
- **WHEN** the factory process was started with `GIT_CONFIG_COUNT=1`,
  `GIT_CONFIG_KEY_0=core.hooksPath` and `GIT_CONFIG_VALUE_0` naming a hook directory, and the
  factory commits
- **THEN** no hook in that directory runs

### Requirement: Transfers layer on the invocation policy
A git transfer — a fetch, a clone or a push — SHALL carry every key of the invocation policy plus
the transfer's own set. A key both sets name SHALL have the same value in both.
**Implements**: FR4 of own-git-invocation-policy

#### Scenario: A transfer carries the invocation policy
- **WHEN** the transfer owner builds a fetch or a clone for any source kind, or a push to origin
- **THEN** its argument list contains every configuration pair of the invocation policy,
  before the subcommand

#### Scenario: A planted pre-push hook does not run on a factory push
- **WHEN** the clone's local configuration points `core.hooksPath` at a directory holding an
  executable `pre-push` hook and the factory pushes a task branch to a bare origin
- **THEN** the hook does not run and the push lands

### Requirement: The factory writes no repository configuration
The factory SHALL write nothing to a clone's repository configuration. The factory git
runner SHALL refuse, before launching a process, a `config` invocation in a write form and a
`remote` invocation in a write form, with an exception naming the refused subcommand and the
policy owner. Read forms SHALL stay allowed. Creating a branch from a remote-tracking ref SHALL
write no upstream configuration.
**Implements**: FR5, FR6, NFR-R2, NFR-O1 of own-git-invocation-policy

#### Scenario: A configuration write is refused before it runs
- **WHEN** production or test code passes `config core.hooksPath <path>` or `remote set-url
  origin <url>` to the runner
- **THEN** the runner throws before launching a process, and the exception names the
  subcommand and the owner

#### Scenario: A configuration read runs
- **WHEN** code passes `config --get <key>` to the runner
- **THEN** the command runs

#### Scenario: Resuming another instance's branch writes no configuration
- **WHEN** a resume recreates the local task branch from its remote-tracking ref
- **THEN** the branch exists, the command succeeds, and the clone's `.git/config` is
  byte-identical before and after

### Requirement: Concurrent work on one clone never contends on its configuration
Concurrent claims, resumes and runs against one clone SHALL NOT fail on the clone's
configuration lock.
**Implements**: NFR-R1 of own-git-invocation-policy

#### Scenario: Eight concurrent claims on one clone
- **WHEN** eight fresh claims start at the same moment against one clone
- **THEN** none fails with a configuration-lock error, and the clone's `.git/config` is
  byte-identical afterwards

### Requirement: The invocation policy has one owner and no bypass
A build gate SHALL fail when a production class outside the two git launchers starts a git
process, and when either launcher starts one without the invocation policy. The gate SHALL
assert that its allowlist is matched exactly, so a new spawn site fails until it is classified.
**Implements**: FR7 of own-git-invocation-policy

#### Scenario: A third launcher fails the build
- **WHEN** a production class outside the two launchers constructs a process whose command is
  git
- **THEN** `./gradlew check` fails naming the class

### Requirement: The policy holds on an adversarial local configuration
The shared real-git clone fixture SHALL plant, as its default posture, a local configuration
carrying a hook directory, a signing program with signing on, a filesystem-monitor command,
and upstream auto-setup forced on. Every spec that drives the factory's git paths on that
fixture SHALL therefore run against the planted vectors. The identity spec SHALL plant the
replace ref on the commit it reads, and SHALL assert that none of the vectors ran.
**Implements**: FR8, M4 of own-git-invocation-policy

#### Scenario: The posture is in force
- **WHEN** a spec builds a clone through the shared fixture
- **THEN** the clone's local configuration holds every planted configuration vector, unless the spec asked
  for the plain posture by name
