# Spec Delta

## Purpose

Gives the factory one notion of "now": a single type every component reads the
current instant through, a single place where real time enters a running
process, and a single virtual fake that lets every spec drive time-dependent
behavior without waiting.

## ADDED Requirements

### Requirement: One type for the current instant
Every production component that reads the current instant SHALL take the
standard JDK instant source as its time dependency. No project-owned clock
type SHALL exist, and no adapter between two clock types SHALL remain.
<!-- implements FR17 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: A component is handed a time source
- **WHEN** a component that stamps or measures time is constructed
- **THEN** its time dependency is the JDK instant source, and the same object
  can be handed to any other time-reading component without conversion

#### Scenario: No second clock type compiles
- **WHEN** a developer looks for a project-defined type for the current instant or its system adapter
- **THEN** none exists in the codebase; the only time type is the JDK one

### Requirement: Real time enters only at the composition root
Outside the composition root, production code SHALL NOT construct real time:
not the system instant source, not the system clock, not a direct read of the
current instant, not the real sleeper, and not a convenience factory that
wires any of these. Every such component SHALL receive its time source from
its caller. A build gate SHALL fail on any new construction site.
<!-- implements FR18 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: A hidden clock fails the build
- **WHEN** a production class outside the composition root reads the current
  instant from the system directly
- **THEN** the architecture gate fails, naming the file

#### Scenario: A hidden sleeper does not compile
- **WHEN** a production class outside the composition root tries to construct
  the real sleeper
- **THEN** the type is not visible to it, and the module does not compile

#### Scenario: The gate does not mistake a declaration for a read
- **WHEN** a production class declares a method named `now` returning an
  instant and reads it from its injected source
- **THEN** the architecture gate passes that file

#### Scenario: The root is the one source
- **WHEN** the process starts
- **THEN** exactly one instant source and one real sleeper are created, in one
  composition-root file, and every time-reading or waiting component receives
  them or a value derived from them

#### Scenario: A policy built on real time has one producer
- **WHEN** the terminal-write retry is looked for across production code
- **THEN** it is constructed in one production file only, the slot wiring,
  derived from the slot's time equipment; every run of a slot dispatches its
  terminal outcome under a retry built there, and a second construction site
  fails the architecture gate naming the file

### Requirement: The two halves of real time travel as one value
A component that needs both the current instant and waiting SHALL take one
time-equipment value carrying the instant source and the sleeper, never the
two as separate parameters. The value SHALL be built once in the composition
root, and no production factory SHALL assemble real time on a caller's behalf.
<!-- implements FR22 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: A policy is built from the equipment it is handed
- **WHEN** a retry, a suppressor or a loop wait is constructed in production
- **THEN** its instant source and its sleeper are the members of the one
  time-equipment value its builder received, and no factory offering a
  "system" default exists for it

#### Scenario: One value replaces the pair on every signature
- **WHEN** a production signature is inspected that used to take an instant
  source and a sleeper side by side
- **THEN** it takes the time-equipment value alone, and its parameter count
  is lower than before, not higher

#### Scenario: A frozen equipment freezes the whole run
- **WHEN** the shipped composition is assembled on a time-equipment value whose
  instant does not advance, and a container run writes its stamps and logs its
  roll-ups
- **THEN** every stamp and every roll-up reads that frozen instant — no
  component observed a second time source

### Requirement: State and the intervals about it share one time source
A component that stamps its state with an instant and measures an interval
about that state (a repeat-suppression window, a staleness window) SHALL
measure the interval on the same instant source it stamps with.
<!-- implements FR19 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Heartbeat suppression follows the heartbeat's time
- **WHEN** the claim heartbeat runs on a virtual instant source and its beat
  fails across a roll-up window on that source
- **THEN** the roll-up and the later recovery are observed as the virtual
  source advances, with no real time passing

#### Scenario: No component holds two clocks
- **WHEN** a component that is handed an instant source is inspected
- **THEN** it constructs no second time source of its own

### Requirement: Tracker and branch timestamps come from the injected source
Every timestamp the factory writes into the tracker (claim markers, heartbeat
comments, decisions, repair and removal markers) or into the task branch (the
task file's creation time) SHALL be read from the injected instant source.
<!-- implements FR20 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: A marker carries the virtual instant
- **WHEN** a tracker adapter built on a virtual instant source writes a claim
  or heartbeat marker
- **THEN** the marker's timestamp equals the virtual source's instant at the
  write

#### Scenario: Both task-branch media stamp alike
- **WHEN** a task is created on the host medium and on the environment medium
  from the same virtual instant source
- **THEN** both task files carry that instant as their creation time

### Requirement: One virtual time source serves every spec
The test fixtures SHALL offer one virtual instant source that a spec advances
by hand, and the test-time gate SHALL fail on any construction of a real clock
in a test source unless the line carries the in-place justification marker.
<!-- implements FR21, NFR-R4 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: One fake drives a mixed flow
- **WHEN** a spec exercises a flow whose components used to take two clock
  types
- **THEN** it builds one virtual source, hands it everywhere, and advances it
  once to move every component together

#### Scenario: A real clock in a spec fails the build
- **WHEN** a spec constructs the system instant source, the system clock or a
  system-wired component without the justification marker
- **THEN** the test-time gate fails, naming the file and line

#### Scenario: A justified real clock passes
- **WHEN** a fixture that assembles the shipped composition constructs real
  time and carries the justification marker beside the call
- **THEN** the test-time gate passes that line
