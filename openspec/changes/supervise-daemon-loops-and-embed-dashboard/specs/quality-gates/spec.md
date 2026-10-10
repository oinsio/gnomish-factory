# Spec Delta: quality-gates

## ADDED Requirements

### Requirement: Stand-in binaries are committed presets
A stand-in binary a test runs SHALL be a committed script under
`test-fixtures`; each scenario SHALL be a committed preset, a table section and
nothing else, so the library commits no links. A spec SHALL select a preset by
name, SHALL NOT write an executable file or shell text, and the only artefact
made at test time SHALL be a symbolic link to the script, made by the one
owner fixture.
<!-- implements FR24, NFR-P2, M11 of supervise-daemon-loops-and-embed-dashboard; design D23, D26, ADR 0015 -->

#### Scenario: A spec selects a preset
- **WHEN** a spec needs a `git` that refuses `fetch` with a documented refusal
- **THEN** it names the `refuse-fetch` preset and hands production the link the
  owner fixture created for it in this JVM
- **AND** the spec reads the refusal text it asserts from the library's data
  file, not from a second literal

#### Scenario: A plain selection is one link per JVM, a scenario with a name one per run
- **WHEN** two specs in one JVM select the same preset without recording
- **THEN** both receive the one link the owner made for it in that JVM
- **AND** a scenario that records, reads a file the spec writes beside the
  link, or needs a name of its own (a hook, a fake-agent scenario) receives a
  per-run link to that link

#### Scenario: A recording scenario gets one link, not one script
- **WHEN** a spec must read back the arguments a stand-in received
- **THEN** the owner fixture creates one symbolic link to the preset in the
  spec's temporary directory and the script writes its log beside the link
- **AND** no file under the spec's directory is executable other than the link

#### Scenario: The library holds no link
- **WHEN** the library is listed
- **THEN** it holds the script, the tables, the data, the steps and the
  process scripts, and no symbolic link
- **AND** the library's own spec enumerates presets from the tables' sections

#### Scenario: The second test starts the stand-in in milliseconds
- **WHEN** two specs in different JVMs run the same preset
- **THEN** the operating system assesses the committed script at most once per
  build, and each start costs milliseconds on macOS as on Linux

### Requirement: Each stand-in behaviour exists once
The stand-in library SHALL hold each behaviour once: two presets that differ
in one word SHALL be one preset taking that word from its link's name; a
process-shaped fake whose whole behaviour is an exit code, a delay or a stall
SHALL be a table preset; and the library's own spec SHALL fail on a table row
that none of its features reaches.
<!-- implements FR26, M11 of supervise-daemon-loops-and-embed-dashboard; design D26 -->

#### Scenario: A refusal that differs only in its text is one preset
- **WHEN** a spec needs a harvest fetch refused with one of the four
  documented refusals
- **THEN** it links the one `harvest-refuse` preset under the refusal's name
  and the script prints that refusal

#### Scenario: A dead row fails the library spec
- **WHEN** a table row is added that no feature of the library's spec reaches
- **THEN** the library spec fails naming the preset and the row

### Requirement: Specs above a subprocess adapter fake its port in process
A spec whose subject is a class above a subprocess adapter SHALL drive an
in-process fake of the adapter's role interface, not a stand-in binary. Every
production holder of the git runner SHALL depend on the role interface, and a
gate SHALL fail on a holder typed as the class.
<!-- implements FR27, M13 of supervise-daemon-loops-and-embed-dashboard; design D27 -->

#### Scenario: A first-push spec runs no process
- **WHEN** `FirstPushSpec` exercises the retry and the remote-tip re-check
- **THEN** the push, the `ls-remote` and the `rev-parse` answers come from the
  scripted in-process fake, and no git process is started

#### Scenario: The fake is as strict as the table was
- **WHEN** the subject calls the fake with an argv no row's prefix matches
- **THEN** the fake throws naming the argv, as the stand-in exits 97

#### Scenario: A process-owning class keeps its stand-in
- **WHEN** the subject is one of the classes that own a `ProcessBuilder`, an
  end-to-end suite, or a scenario that hands part of the argv to the real git
- **THEN** it runs a stand-in or the real binary, and the fake is not used

#### Scenario: A termination spec interrupts a blocked call, not a sleep
- **WHEN** a reconciler termination spec interrupts a reconciliation in flight
- **THEN** the fake's blocked call is where the interrupt lands, released by
  the spec, and the subject classifies nothing

#### Scenario: A holder typed as the class fails the gate
- **WHEN** a production class outside the adapter class and the composition
  root declares a field or parameter of the runner's concrete type
- **THEN** the boundary gate in `:bootstrap` fails naming the file

### Requirement: One owner of executable files in test sources
A build gate SHALL fail on any test-source site, outside the owner fixture and
the stand-in library, that creates an executable file or carries shebang text,
with named exemptions for scripts mounted into a container and for specs of
shipped scripts, and SHALL assert that its scan reached every exempted file.
<!-- implements FR24, M11 of supervise-daemon-loops-and-embed-dashboard; design D23 -->

#### Scenario: A spec that writes its own script fails the gate
- **WHEN** a test source outside the owner fixture and the named exemptions
  marks a file executable or contains shebang text
- **THEN** the owner gate in `:bootstrap` fails naming the file

#### Scenario: A moved exemption fails instead of widening the allowance
- **WHEN** an exempted file is renamed or moved without updating the gate
- **THEN** the gate fails because its scan did not reach the listed file

### Requirement: Out-of-process bootstrap suites are outside the mutation scan
The `bootstrap` suites that drive a real Docker daemon or a Gitea container and
whose production classes are all covered by in-process specs SHALL be listed
in the module's excluded test classes with the rationale the `testing.md` bar
requires; suites that run on the fake sandbox docker over a real bare
repository SHALL stay in the scan.
<!-- implements FR25 of supervise-daemon-loops-and-embed-dashboard; design D24 -->

#### Scenario: The nine Docker and Gitea suites are excluded
- **WHEN** PIT runs for `:bootstrap`
- **THEN** its coverage pass runs none of `FrozenTimeEquipmentRunSpec`,
  `GiteaBestEffortPushE2ESpec`, `GiteaCrossInstanceResumeE2ESpec`,
  `RunParkKillPointContainerE2ESpec`, `RunParkRecordingContainerE2ESpec`,
  `SandboxLifecycleCrossInstanceE2ESpec`, `SandboxLifecycleLegacyIdentityE2ESpec`,
  `SandboxLifecycleRemnantReapE2ESpec`, `GiteaActionsStageVerifyE2ESpec`
- **AND** every mutant of the classes they reach is still killed by an
  in-process spec

#### Scenario: The fake-docker suites stay in the scan
- **WHEN** PIT runs for `:bootstrap`
- **THEN** `ContainerResumeEscalationSpec`, `ContainerResumeRunnerSpec` and
  `TransitionKillPointSpec` remain among its covering tests
