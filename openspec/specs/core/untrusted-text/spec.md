# untrusted-text Specification

## Purpose

One carrier type for every piece of text that enters the factory from outside
its trust boundary, so that where the text came from travels with the text,
sinks can only receive it through a neutralizing exit, and the classic
laundering move — concatenating it into a factory-authored string — is safe by
construction.

## Requirements

### Requirement: Untrusted text is carried by one typed value with provenance
Text from outside the trust boundary — subprocess output, in-container command
output, agent output, tracker-sourced strings, the target repository's
manifest, and values read back from a task-branch document — SHALL be carried
by one value type from the moment it enters the process, tagged with its
provenance. One further provenance SHALL exist for text that is *not* from
outside the trust boundary: an operator's own command-line argument, for the
case where a syntax gate has refused it and the refusal is published to the
tracker — a refused ref name is refused precisely for holding whitespace or a
control character, so quoting it back needs an exit like any other text. A
second such provenance SHALL exist for the factory's own prose: a field that
carries captured text on one path carries a factory-composed sentence on
another — a refusal, a disposition, an empty detail — and that sentence SHALL
be minted in its own family rather than in the family of whatever capture sits
beside it. A sentence that quotes a capture SHALL stay factory-composed only
when the quote left its carrier through an exit first; a sentence interpolating
a capture raw SHALL keep the capture's family. The provenance set SHALL
otherwise stay closed: text with no family here has no mint. The type SHALL expose the raw text only through one accessor
reserved for exit owners, and SHALL render itself by default in the log-safe
form, so string concatenation of the value yields neutralized text. Equality
SHALL be by the text alone: provenance is evidence a report may name, never a
policy input and never part of the value's identity, so a carrier written to a
durable medium and read back — under the provenance of the medium it was read
from — equals the carrier that was written.
<!-- implements FR1, FR4 of type-untrusted-text -->

#### Scenario: Concatenation is safe by default
- **WHEN** a carrier holding an ANSI cursor sequence and a newline is
  concatenated into a factory-authored message
- **THEN** the resulting string contains the sequence and the newline only as
  inert visible escapes, identical to the carrier's log exit

#### Scenario: A value read back from a document equals the value written
- **WHEN** a carrier minted at capture is written to a task-branch document
  and the reader mints the value it lifts back out with the document's own
  provenance
- **THEN** the two carriers are equal, hash alike, and render byte-identically
  through every exit

#### Scenario: A refused operator argument is quoted back through an exit
- **WHEN** a base-ref resolution refuses the operator's `--base` argument
  because it is not a well-formed ref name, and the park report names the
  offending value
- **THEN** the value reaches the report as a carrier under the operator
  provenance and is published through the comment exit, so a control character
  the grammar refused cannot reach the tracker comment raw

#### Scenario: A factory-composed sentence is not filed under the capture it quotes
- **WHEN** a check reports that it cannot verify, with a reason the factory
  wrote and a detail holding the command's captured output
- **THEN** the reason names the factory as its provenance and the detail names
  the capture's, and both render through the same exits

#### Scenario: Provenance travels with the value
- **WHEN** a carrier minted from git stderr reaches a report three calls away
- **THEN** the report can name its provenance as subprocess output without
  any other source

### Requirement: Exits render per consumer and equal the owner primitives
The carrier SHALL offer four exits: a log exit (one line, capped), a console
exit (visible caret/escape notation, line structure kept), a comment exit
(fenced, labeled, mentions and issue references neutralized, line structure
kept), and the comment exit's inline shape (the same neutralization without
the label and the fence, for a field quoted inside a line the factory wrote
itself). Each exit SHALL compute exactly what the untrusted-text owner's
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

#### Scenario: A report the factory assembled is published unfenced
- **WHEN** a report the factory composed — a finish summary, a checkpoint park
  report — quotes untrusted fields and is published to the tracker
- **THEN** each quoted field leaves its carrier through the comment exit's
  inline shape, the assembled block is published as it stands, and no label or
  fence names the factory's own report lines as machine output

### Requirement: Raw access is confined to annotated exit owners
The raw accessor SHALL be callable only from classes marked as exit owners by
an annotation the carrier's module defines: the exits, the writers that
carry raw bytes to a machine medium (state and ledger JSON), and the findings
funnel entry. An architecture gate SHALL fail the build on any other caller;
the gate SHALL key on the annotation, never on a list of class names kept in
build logic. The set SHALL NOT be widened to admit code that reads captured
text in order to parse it — that is a separate way out with a separate
allowlist — and the gate SHALL pin the set, so a class joining it fails the
build until the growth is acknowledged.
<!-- implements FR3, FR7, NFR-R2, NFR-S1 of type-untrusted-text -->

#### Scenario: A raw read outside an exit fails the build
- **WHEN** a production class without the exit annotation calls the raw
  accessor
- **THEN** the architecture gate fails naming the class and the call

#### Scenario: A machine writer is an exit
- **WHEN** the task-branch state writer serializes a carrier
- **THEN** it writes the raw text (JSON encoding bounds it) and is annotated
  as an exit, and reading the same document back mints a branch-document
  carrier; a document written before the carrier existed reads back the same
  way, because the wire format is unchanged

### Requirement: Machine-readable capture is parsed through its own annotated way out
Captured text that the factory reads to answer a question about its own
machinery — a commit id, a ref list, a remote URL, a container's state — SHALL
stay carried by the type, and SHALL be read for parsing through a dedicated
accessor callable only from classes marked by a second annotation the
carrier's module defines. A class so marked SHALL convert the text into a
value that is no longer untrusted text: a typed value, a string that passed a
named syntax gate, or a carrier re-minted with another provenance. Returning
the text unchanged as a plain string SHALL NOT qualify; a reader that yields a
document's content rather than an answer about it SHALL carry the carrier
onward to the machine writer that consumes it. Where a parser's converted
value is itself a string — a commit id, a ref name — it SHALL state what makes
that string inert: a named syntax gate it applies, or the fixed shape it
checks. The carrier SHALL additionally
answer emptiness, substring and length questions to any caller, since those
yield no text. Both annotated sets SHALL be pinned by the architecture gate
with seeded-violation coverage.
<!-- implements FR3, FR10, NFR-S1 of type-untrusted-text -->

#### Scenario: A parser reads the captured bytes and yields a value
- **WHEN** the commit-id reader takes the standard output of a revision
  resolution and returns a verified commit id
- **THEN** it reads through the parsing accessor, is marked as a parser, and
  the bytes it parsed are the bytes git wrote — uncapped and unflattened

#### Scenario: A parser that hands the text back unchanged fails the gate
- **WHEN** a class marked as a parser returns the captured text as a plain
  string instead of converting it
- **THEN** the gate fails naming the method, and points at carrying the
  carrier onward instead

#### Scenario: Reading a branch document is not parsing
- **WHEN** a branch file's content is read at a tip and handed to the
  task-branch document reader
- **THEN** the content travels as a carrier the whole way, and only the
  document reader — a machine writer, annotated as an exit — takes the raw
  text out, minting each field it lifts as a branch-document value

#### Scenario: A transformation inside the carrier needs no annotation
- **WHEN** a carrier longer than a caller's budget is capped through the
  carrier's own truncation, which keeps the head and the tail and marks what
  it dropped
- **THEN** the result is a carrier of the same provenance, the caller needed
  no exit-owner or parser annotation, and the exit allowlist is unchanged

#### Scenario: A question about the text needs no annotation
- **WHEN** a caller asks whether captured output is blank, or contains a
  known marker
- **THEN** it answers without any annotation, because a boolean carries no
  text out of the carrier

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
