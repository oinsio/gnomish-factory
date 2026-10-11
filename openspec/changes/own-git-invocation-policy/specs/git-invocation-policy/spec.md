# git-invocation-policy

## Purpose

Governs how every git process the factory starts is invoked. The factory states its own
policy on each command line and does not depend on the operator clone's shared configuration
file: the file is neither read as trusted policy nor written as storage.

## ADDED Requirements

### Requirement: Every factory git process carries one invocation policy
Every git process the factory starts — through the factory git runner or through the
git-object library — SHALL carry one invocation policy, built by one owner, as command-scope
configuration placed before the subcommand. The policy SHALL neutralize, by value: hooks,
commit signing, tag signing, the filesystem monitor, branch upstream auto-setup, automatic
maintenance, automatic garbage collection, and replace refs. The policy SHALL NOT set any key
that carries the operator's credentials or transport (credential helpers, the SSH command,
URL rewrites); those stay ambient for the named remote.
<!-- implements FR1, FR2, NFR-S2 of own-git-invocation-policy -->

#### Scenario: A planted local configuration runs nothing
- **WHEN** the clone's local configuration plants a hook directory with executable
  `pre-commit` and `reference-transaction` hooks, `commit.gpgSign=true` with a `gpg.program`,
  and a `core.fsmonitor` command, and the factory commits in a worktree, updates a ref, writes
  a commit through the git-object library, and adds a worktree
- **THEN** no planted program runs, and every operation succeeds

#### Scenario: Replace refs do not change what the factory reads
- **WHEN** the clone holds a `refs/replace/` entry substituting a commit on a task branch
- **THEN** the factory's reads of that branch see the original commit

#### Scenario: Factory commits start no background maintenance
- **WHEN** the factory commits in a clone whose loose-object count exceeds the automatic
  maintenance threshold
- **THEN** no maintenance or gc process is started by that commit

#### Scenario: Operator credentials still reach origin
- **WHEN** the operator's global configuration names a credential helper and the factory
  pushes to `origin`
- **THEN** the helper is consulted as before

### Requirement: Inherited per-process configuration is stripped for every command
Every factory git process SHALL be started with inherited `GIT_CONFIG_PARAMETERS` and
`GIT_CONFIG_COUNT` removed from its environment, whatever the subcommand.
<!-- implements FR3 of own-git-invocation-policy -->

#### Scenario: An inherited configuration pair does not reach a commit
- **WHEN** the factory process was started with `GIT_CONFIG_COUNT=1`,
  `GIT_CONFIG_KEY_0=core.hooksPath` and `GIT_CONFIG_VALUE_0` naming a hook directory, and the
  factory commits
- **THEN** no hook in that directory runs

### Requirement: Transfers layer on the invocation policy
A git transfer SHALL carry every key of the invocation policy plus the transfer's own set. A
key both sets name SHALL have the same value in both.
<!-- implements FR4 of own-git-invocation-policy -->

#### Scenario: A transfer carries the invocation policy
- **WHEN** the transfer owner builds a fetch or a clone for any source kind
- **THEN** its argument list contains every configuration pair of the invocation policy,
  before the subcommand

### Requirement: The factory writes no repository configuration
The factory SHALL write nothing to a clone's repository configuration. The factory git
runner SHALL refuse, before launching a process, a `config` invocation in a write form and a
`remote` invocation in a write form, with an exception naming the refused subcommand and the
policy owner. Read forms SHALL stay allowed. Creating a branch from a remote-tracking ref SHALL
write no upstream configuration.
<!-- implements FR5, FR6, NFR-R2, NFR-O1 of own-git-invocation-policy -->

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
<!-- implements NFR-R1 of own-git-invocation-policy -->

#### Scenario: Eight concurrent claims on one clone
- **WHEN** eight fresh claims start at the same moment against one clone
- **THEN** none fails with a configuration-lock error, and the clone's `.git/config` is
  byte-identical afterwards

### Requirement: The invocation policy has one owner and no bypass
A build gate SHALL fail when a production class outside the two git launchers starts a git
process, and when either launcher starts one without the invocation policy. The gate SHALL
assert that it reached every production source root.
<!-- implements FR7 of own-git-invocation-policy -->

#### Scenario: A third launcher fails the build
- **WHEN** a production class outside the two launchers constructs a process whose command is
  git
- **THEN** `./gradlew check` fails naming the file

### Requirement: The policy holds on an adversarial local configuration
The shared real-git clone fixture SHALL plant, as its default posture, a local configuration
carrying a hook directory, a signing program with signing on, a filesystem-monitor command,
upstream auto-setup forced on, and a replace ref. Every spec that drives the factory's git
paths on that fixture SHALL therefore run against the planted vectors. A spec SHALL assert
that none of them ran.
<!-- implements FR8, M4 of own-git-invocation-policy -->

#### Scenario: The posture is in force
- **WHEN** a spec builds a clone through the shared fixture
- **THEN** the clone's local configuration holds every planted vector, unless the spec asked
  for the plain posture by name
