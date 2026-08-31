# github-tracker — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Label-to-type mapping with configurable rule
The GitHub adapter SHALL derive the type designator from issue labels using
an operator-configurable mapping in the adapter's own config subsection,
defaulting to the `type:*` prefix rule (label `type:bugfix` → designator
`bugfix`). Multiple matching labels SHALL be reported as a type conflict.
Labels not matching the rule SHALL be ignored for typing. The mapping SHALL
be config only — no adapter code change is needed for a repo using a
different label scheme.
<!-- implements FR2 of add-pipeline-routing -->

#### Scenario: Default prefix rule derives the type
- **WHEN** an issue carries labels `type:research` and `priority:high`
- **THEN** the adapter reports the designator `research`

#### Scenario: Operator remaps the rule
- **WHEN** the adapter config maps the prefix `kind/` instead
- **THEN** an issue labeled `kind/bug` reports designator `bug` and
  `type:bug` is ignored

#### Scenario: Two matching labels report a conflict
- **WHEN** an issue carries `type:bugfix` and `type:feature`
- **THEN** the adapter reports a type conflict listing both designators
