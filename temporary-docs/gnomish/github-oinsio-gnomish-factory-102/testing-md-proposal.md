Proposed paragraph for `.claude/rules/testing.md`, "Git fixtures are adversarial by default",
after the paragraph "The operator's git configuration is adversarial the same way…" (task 4.2):

  The clone's own local configuration is adversarial too (design D6 of
  `own-git-invocation-policy`): every working repository `BareGitRepoFixture.initWorkingRepo`
  builds carries, through `AdversarialLocalConfig` in `:test-fixtures`, a `core.hooksPath`
  directory whose `pre-commit`, `post-checkout` and `reference-transaction` hooks record their run
  and exit 1, `commit.gpgSign=true` with a `gpg.program` that records and exits 1, a
  `core.fsmonitor` command that records, and `branch.autoSetupMerge=always`. Every planted program
  is the committed preset `hook-record-refuse` behind a per-repository link under
  `.git/gnomish-planted/`, so its marker is that link's `.log`. A git path that runs without the
  factory's invocation policy (`GitInvocation`) therefore fails an existing spec instead of
  waiting for a regression spec; a spec asserting the policy held calls
  `assertNothingPlantedRan(repo)`, which names each program that ran. The fixture's configuration
  writes go through `FixtureGitConfig`, since the runner refuses them. A spec whose git runs
  outside the policy on purpose asks for `initPlainWorkingRepo` by name, with a comment giving
  the reason; the plain posture is never inherited silently
