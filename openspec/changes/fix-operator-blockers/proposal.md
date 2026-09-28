# Proposal: fix-operator-blockers

## Why

The first operator stand (a separate test project driving the factory from a
locally built jar) could not run a single task on a fresh installation without
hand-written workarounds. Re-checked against the code on 2026-09-27, the
defects are still live:

- **The gnome cannot write files.** The factory launches `claude -p` with
  `--allowedTools`, which pre-approves a tool by name but does not lift the
  CLI's own permission gate. In print mode nobody is there to approve, so
  every `Edit`/`Write` is refused, the gnome burns its turns, and the round
  ends with no result — while the factory only reports "the judge says the file
  is unchanged". Every operator starts by writing a wrapper script that adds
  `--permission-mode acceptEdits`; in container mode that wrapper has to be
  baked into the image and `factory.agent-cli-binary` switched per binding.
- **The operator's own MCP servers join every round.** The same wrapper adds
  `--strict-mcp-config`: without it the operator's editor/browser servers load
  into gnome and judge rounds (about 20 KB of starting context each), and a
  project `.mcp.json` in the working copy can start a server process.
- **Nobody says how the agent logs in inside the box.** The container child
  environment starts empty and no guide names the agent's credential. The
  working answer (`claude setup-token` → `CLAUDE_CODE_OAUTH_TOKEN`, or an API
  key) is found by reading the CLI's documentation, then forwarded through the
  generic passthrough, which also hands it to every command check.
- **The documented default-binding key does nothing.** `factory.bindings.default`
  — the key the guide and the factory's own error message name — is silently
  ignored; only an undocumented `default-binding` spelling binds.
- **A relative `--dir` breaks git mode**, and **an unknown flag is silently
  swallowed** (`gnomish status --task=<id>` quietly shows the overview), so a
  typo looks like success.
- **Three serve observability sinks rely on call order for visibility.** The
  slot runner receives its ledger writer and, under `--drain`, its drain
  report and run-summary accumulator after construction, into plain fields
  read on slot threads. Today every write precedes the start of the feed
  thread, so the values are visible; a reordering of the start-up sequence
  would silently drop the `taskOutcome` and `runSummary` ledger lines the
  operator judges the daemon by. The project's immutability rule requires
  such fields to be `volatile` with the forcing cycle named
  (found by the 2026-08-28 code-quality audit, still open).
- **A clean stop looks like a tracker outage.** Stopping an idle `serve` with
  Ctrl+C prints a WARN `[GF064] sweep listing failed` with two stack traces:
  the stop interrupts the reaper mid-request, the GitHub HTTP client reports
  the interrupt as a network failure (retried, then "failed after retries"),
  and the reaper treats it as an outage and forgets its observation windows.
  The feed's decline of finished tasks and the tracker health counter
  misread an interrupt the same way, and so does the claim heartbeat's beat
  when a stop arrives while claims are held. The `factory-serve` capability
  already requires stop-caused interrupts to be logged without stack traces;
  these paths break it.

These block the first release: a release that works only after the operator
reverse-engineers two wrapper scripts is not a release.

## What Changes

- **MODIFIED**: the agent-CLI adapter launches executor rounds with a
  permission mode that auto-approves file edits inside the working directory,
  and judge votes with a mode that never approves an edit and never waits for
  an answer. Both are adapter policy, like the judge's read-only tool set — not
  a manifest setting.
- **MODIFIED**: every agent round and judge vote excludes all MCP servers.
- **MODIFIED**: the agent's own credentials (`CLAUDE_CODE_OAUTH_TOKEN`,
  `ANTHROPIC_API_KEY`) are forwarded to agent rounds and judge votes through
  the existing AI seam, read live from the factory environment — with no
  passthrough configuration and without reaching command checks.
- **ADDED**: the documented `factory.bindings.default` key binds.
- **ADDED**: one argument owner for every subcommand resolves `--dir` to an
  absolute path at parse time and rejects an option the subcommand does not
  accept, naming it and listing what is accepted.
- **MODIFIED** (code invariant): the slot runner's three post-construction
  fields are safely published, independent of start-up order.
- **MODIFIED** (docs): the sandbox guide and the reference image README gain
  "how the agent authenticates in the box"; the guides recommend a narrow
  token for `gh` in the deliver stage instead of reusing the tracker token;
  every command example under `docs/` uses the `--key=value` form the parser
  accepts; wrapper-script advice is removed from every document under
  `docs/`, the reference-pipelines note included.
- **ADDED**: an interrupted tracker call is a cancellation, not an outage —
  the GitHub HTTP client stops at once without retrying and keeps the
  thread's interrupt; the external-check poll lets the cancellation propagate
  instead of reporting "cannot verify"; and the daemon's reaper, finished-task
  decline, tracker health counter and claim heartbeat treat a failure on an
  interrupted thread as the stop it is.

## Capabilities

### New Capabilities

- `cli-arguments`: how every `gnomish` subcommand treats its command line —
  unknown options are usage errors, `--dir` is resolved once to an absolute
  path, Spring property options pass through.

### Modified Capabilities

- `agent-executor`: "Hard-wired adapter policy" gains the per-role permission
  mode and the MCP exclusion; "CLI child environment is allowlisted" names the
  agent credentials carried by the AI seam.
- `plugin/adapter-binding-registry`: adds a requirement that the documented
  default-binding key binds.
- `tracker-port`: adds a requirement that an interrupted call is a
  cancellation, not an outage.
- `github-tracker`: adds a requirement that an interrupt is not a transport
  failure.
- `factory-serve`: adds a requirement that daemon workers treat an interrupt
  as a stop, not a failure.

## Goals

- **G1**: a fresh installation runs a task end to end in host and container
  mode with the stock `claude` binary — no wrapper script, no per-binding
  `agent-cli-binary`.
- **G2**: the operator learns how the agent authenticates in the box from the
  sandbox guide alone.
- **G3**: every configuration key and flag the documentation names does what
  it says, and a flag the factory does not know fails loudly.
- **G4**: stopping an idle daemon with a signal leaves no WARN, ERROR, or
  stack trace in the log after the serve-stopping anchor.

## Non-Goals

- **NG1**: isolating the agent CLI from settings files in the working copy
  (`.claude/settings.json` widening the tool policy) — tracked separately and
  sequenced after the agent backend SPI design.
- **NG2**: a hard deny list for the judge (sub-agents, scheduling tools). This
  change only verifies what the judge's new permission mode already denies
  (Q1).
- **NG3**: making the permission mode a manifest or operator setting.
- **NG4**: operator configuration levels and project registration
  (`add-project-registry`), release packaging (`add-release-pipeline`), the
  sandbox base image, and `gnomish doctor`.
- **NG5**: accepting space-separated option values (`--out path`); the
  documentation is corrected instead.
- **NG6**: a separate factory-owned credential for `gh`; the guides recommend
  a narrow token only.
- **NG7**: stopping the sandbox lifecycle tick and the worktree janitor before
  the process-tree kill. Their tick can still fail on a subprocess the kill
  ends; the window is narrow and the fix is a stop hook per worker, recorded
  as a follow-up.

## Users & Scenarios

- **U1**: an operator installs the factory, points it at a project, and runs
  `gnomish run` in host mode — the gnome edits files on the first attempt.
- **U2**: the same operator switches to the default container binding, puts
  the agent token in the factory's environment, adds the provider host to the
  egress allowlist, and the round authenticates.
- **U3**: an operator (or an AI agent driving the CLI) types
  `gnomish status --task=<id>` and is told the option is unknown and that the
  task is positional.
- **U4**: an operator runs `gnomish run --dir=. --task="..."` from inside the
  clone and git mode starts.
- **U5**: an operator starts `serve`, waits until it is up and idle, and
  presses Ctrl+C — the log ends with the stop anchors and nothing that reads
  like a fault.

## Requirements

### Functional

- **FR1**: executor rounds SHALL launch with the permission mode that
  auto-approves file edits inside the working directory; the decision-file
  write allowance stays as it is.
- **FR2**: judge votes SHALL launch with the permission mode that denies every
  tool call outside the pre-approved read-only set without waiting for an
  answer.
- **FR3**: executor rounds and judge votes SHALL launch with all MCP server
  configuration excluded.
- **FR4**: the permission mode and the MCP exclusion SHALL NOT be settable from
  the manifest or operator configuration; the manifest `settings` key set is
  unchanged. This already holds today — the settings validator rejects every
  unrecognized key — so the change pins it with a spec row and adds no logic.
- **FR5**: `CLAUDE_CODE_OAUTH_TOKEN` and `ANTHROPIC_API_KEY`, when set in the
  factory environment, SHALL be set on every agent round and judge vote, and on
  no other exec.
- **FR6**: `factory.bindings.default` SHALL bind the default stage binding in
  every property source form (command line, file, system property).
- **FR7**: every subcommand SHALL resolve `--dir` against the working
  directory to an absolute, normalized path before any component reads it.
  Whether `--dir` is required stays as it is per subcommand: `status` and
  `usage` keep refusing an absent `--dir`; `run`, `take`, `serve`, `board` and
  `dashboard` keep defaulting to the working directory.
- **FR8**: every subcommand SHALL reject an option it does not accept with a
  usage error naming the option and listing the accepted ones; Spring property
  options (dotted names such as `--factory.*`, `--spring.*`, `--logging.*`)
  and Spring Boot's own `--debug` / `--trace` switches SHALL pass through.
- **FR9**: the sandbox guide and the reference image README SHALL state how the
  agent CLI authenticates in the box (token variables, the provider host on the
  egress allowlist, subscription token via `claude setup-token`); the guides
  SHALL recommend a fine-grained token (contents + pull requests) for `gh`;
  every command example under `docs/` (the dashboard, run and operator guides,
  `docs/reference-pipelines.md`) SHALL use `--key=value`; no document under
  `docs/` SHALL mention an agent-CLI wrapper script.
- **FR10**: every value the slot runner receives after construction (the
  ledger writer, the drain report, the run-summary accumulator) SHALL be
  visible to every slot thread that runs after it was set, whatever the order
  in which threads were started.
- **FR11**: the GitHub HTTP client SHALL treat an interrupt of the calling
  thread as a cancellation: no further attempt, the thread's interrupt left
  set, and a failure distinct from a transport failure, so no tracker path
  reports it as the tracker being unavailable and the external-check poll does
  not report it as "cannot verify" — it propagates as the stop it is.
- **FR12**: every daemon site that catches a failed tracker call — the
  reaper's sweep listing, the feed's finished-task decline, the tracker
  health counter, the claim heartbeat's beat — SHALL treat a failure on an
  interrupted thread as a stop:
  no WARN or ERROR line, no stack trace, no outage bookkeeping (forgotten
  observation windows, a listing-failed signal, a failure count), and no
  further tracker call from that loop.

### Non-Functional

- **NFR-S1**: no rendered command line SHALL ever contain the permission mode
  that skips all checks; the edit auto-approval stays confined to the working
  directory the CLI is launched in.
- **NFR-S2**: a `.mcp.json` in the working copy SHALL NOT start a process in
  any round.
- **NFR-S3**: agent credentials SHALL NOT reach command checks through the
  AI seam; an operator who also lists them in passthrough widens that
  deliberately.
- **NFR-R1**: a flag the parser rejects SHALL fail before any claim, branch or
  box is created.
- **NFR-R2**: a `taskOutcome` or `runSummary` ledger line SHALL NOT be lost
  to a stale read of a sink that was already set (FR10).
- **NFR-R3**: an interrupt SHALL consume no retry budget and SHALL NOT become
  a `TrackerUnavailableException` (FR11).
- **NFR-O1**: a usage error SHALL name the offending option and the accepted
  set in one message, so a single run shows the fix.
- **NFR-O2**: a signal stop of an idle daemon SHALL add no WARN or ERROR line
  and no stack trace after the serve-stopping anchor (FR12); a genuine
  tracker failure on a thread that was not interrupted keeps its WARN.
- **NFR-C1**: excluding MCP servers removes their tool definitions from every
  round's starting context.
- **NFR-P1**: none — argument checks are in-memory and run once per process.

## Operator Experience Criteria

- **UX1**: the sandbox guide answers "how does the agent log in inside the box"
  in one section with a copy-paste configuration.
- **UX2**: an unknown flag error reads like
  `unknown option --task for 'gnomish status'; accepted: --dir, --json; the task
  id is positional`.
- **UX3**: no guide or error message mentions a wrapper script for the agent CLI.

## Success Metrics

- **M1**: the fake-agent argv capture shows `--permission-mode acceptEdits` and
  `--strict-mcp-config` in 100% of executor rounds, and `--permission-mode
  dontAsk` and `--strict-mcp-config` in 100% of judge votes, in host and
  container mode.
- **M2**: all 7 subcommand parsers reject an unknown option and absolutize a
  relative `--dir` (one data-driven spec over every parser).
- **M3**: on the operator stand, the `claude-gnome` wrapper is deleted, the
  `agent-cli-binary` and default-binding workarounds are removed from the
  launcher, and one task completes in host mode and one in container mode.
- **M4**: `factory.bindings.default=host` binds through a real Spring
  `Binder` in a spec.
- **M5**: an interrupted GitHub call reaches WireMock exactly once, and a
  real-thread spec that stops the standing reaper mid-listing records zero
  WARN lines; each of the four catch sites has a spec row with the interrupt
  set and a control row without it.

## Open Questions

- **Q1**: does the judge's `dontAsk` mode also deny tools that need no
  permission (sub-agents, scheduling)? Verified against the pinned CLI in the
  paid smoke layer; if not, the deny list stays out of scope (NG2) and is
  recorded as a follow-up.
- **Q2**: is there a reproducible root cause for the relative `--dir` failure
  beyond "the path is relative"? The fix at the argument owner holds either
  way; the reproduction spec (task 1) records what actually failed.

## Impact

- `adapters/agent`: `AgentInvocationOptions`, `AgentCommandLine`,
  `AgentAiSeam`, their specs. `:bootstrap`: the fake agent's
  `GNOMISH_FAKE_CAPTURE_ARGV` hook (present in `fake-agent.sh`, used by no
  spec today) is wired into the module's host and container E2E specs; a
  source-scan spec pins the credential names to the seam.
- `sandbox/core`: `BindingProperties`.
- `application`: `TakeSlotRunner` (three fields become `volatile`; javadoc
  names the forcing cycle). No capability requirement changes: FR10 is a code
  invariant of `process-invariants.md`, not operator-visible behavior.
- `application`: `ArgumentsParsingSupport` and the seven `*ArgumentsParser`
  classes.
- `adapters/github`: `GithubHttpClient`, a new cancellation exception in the
  shared HTTP core, `GithubWorkflowRunPoll` (lets it propagate), their specs.
- `application`: `Reaper`, `FinishedDecline`, `HeartbeatBeater`;
  `gnomish-plugin-api`: `TrackerHealthTracker`. No port signature changes.
- Docs: `operator-guide-sandbox.md`, `docs/examples/sandbox-image/README.md`,
  `operator-guide-dashboard.md`, `operator-guide.md` (deliver-stage token,
  board example), `operator-guide-run.md` (resume example),
  `docs/reference-pipelines.md` (wrapper-script note).
- No new dependencies. Overlaps: `remove-interactive-console` and
  `make-run-headless` also touch `RunArgumentsParser` (rebase order in design).
