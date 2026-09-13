# untrusted-text — delta for type-untrusted-text

## Purpose

One carrier type for every piece of text that enters the factory from outside
its trust boundary, so that where the text came from travels with the text,
sinks can only receive it through a neutralizing exit, and the classic
laundering move — concatenating it into a factory-authored string — is safe by
construction.

## ADDED Requirements

### Requirement: Untrusted text is carried by one typed value with provenance
Text from outside the trust boundary — subprocess output, in-container command
output, agent output, tracker-sourced strings, the target repository's
manifest, and values read back from a task-branch document — SHALL be carried
by one value type from the moment it enters the process, tagged with its
provenance. The type SHALL expose the raw text only through one accessor
reserved for exit owners, and SHALL render itself by default in the log-safe
form, so string concatenation of the value yields neutralized text.
<!-- implements FR1, FR4 of type-untrusted-text -->

#### Scenario: Concatenation is safe by default
- **WHEN** a carrier holding an ANSI cursor sequence and a newline is
  concatenated into a factory-authored message
- **THEN** the resulting string contains the sequence and the newline only as
  inert visible escapes, identical to the carrier's log exit

#### Scenario: Provenance travels with the value
- **WHEN** a carrier minted from git stderr reaches a report three calls away
- **THEN** the report can name its provenance as subprocess output without
  any other source

### Requirement: Exits render per consumer and equal the owner primitives
The carrier SHALL offer three exits: a log exit (one line, capped), a console
exit (visible caret/escape notation, line structure kept), and a comment exit
(fenced, labeled, mentions and issue references neutralized, line structure
kept). Each exit SHALL compute exactly what the untrusted-text owner's
primitive computes for the same raw text, and the default rendering SHALL
equal the log exit byte for byte, asserted over a common adversarial corpus
for every provenance.
<!-- implements FR2, NFR-S2 of type-untrusted-text -->

#### Scenario: Exits equal the primitives
- **WHEN** every corpus entry is minted with every provenance and rendered
  through each exit
- **THEN** each rendering equals the owner primitive applied to the raw text,
  and the default rendering equals the log exit

#### Scenario: The comment exit neutralizes mentions and fences safely
- **WHEN** a carrier holding `@team`, `#123`, and a line of five backticks is
  rendered through the comment exit
- **THEN** the mention and the reference are broken with a zero-width space,
  the block is fenced with a run longer than five, labeled as untrusted
  machine output, and every original line break is preserved

### Requirement: Raw access is confined to annotated exit owners
The raw accessor SHALL be callable only from classes marked as exit owners by
an annotation the carrier's module defines: the three exits, the writers that
carry raw bytes to a machine medium (state and ledger JSON), and the findings
funnel entry. An architecture gate SHALL fail the build on any other caller;
the gate SHALL key on the annotation, never on a list of class names kept in
build logic.
<!-- implements FR3, FR7, NFR-S1 of type-untrusted-text -->

#### Scenario: A raw read outside an exit fails the build
- **WHEN** a production class without the exit annotation calls the raw
  accessor
- **THEN** the architecture gate fails naming the class and the call

#### Scenario: A machine writer is an exit
- **WHEN** the task-branch state writer serializes a carrier
- **THEN** it writes the raw text (JSON encoding bounds it) and is annotated
  as an exit, and reading the same document back mints a branch-document
  carrier

### Requirement: Sinks receive untrusted text only through an exit
No logging call, throwable constructor, console print, or tracker write SHALL
take a carrier as a direct argument; the value passes an exit first, so intent
is visible at the site. Every accessor that yields text from a capture family
SHALL return the carrier, not a plain string. Both rules SHALL be enforced by
architecture gates with seeded-violation coverage, and the former
accessor-name gate SHALL be retired.
<!-- implements FR5, FR7, NFR-S1 of type-untrusted-text -->

#### Scenario: A carrier passed straight to a logger fails the gate
- **WHEN** a production log call takes a carrier-typed expression as an
  argument
- **THEN** the gate fails naming the site and asks for an exit

#### Scenario: A capture accessor returning a plain string fails the gate
- **WHEN** a production method in a capture family's vocabulary (standard
  error, standard output, captured output, agent session id, tracker title,
  claim holder, manifest label) returns a plain string
- **THEN** the gate fails naming the method

#### Scenario: An exception carrying subprocess output renders safely
- **WHEN** an exception constructed with a carrier is logged as the trailing
  argument
- **THEN** its message is the carrier's log exit — single-line, capped, inert
