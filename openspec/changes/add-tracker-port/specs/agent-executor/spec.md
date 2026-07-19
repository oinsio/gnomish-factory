# agent-executor — delta

## MODIFIED Requirements

### Requirement: Manifest settings with strict validation
The manifest `settings` map of an `agent-cli` executor and of a judge check SHALL accept exactly `allowedTools`, `disallowedTools`, `maxTurns`, and `roundTimeout`; any unknown key SHALL be a startup error raised before any dialog, naming the stage/check and the offending key. Installation-level configuration — the CLI binary path (default: `claude` from PATH) and environment passthrough to the CLI process — SHALL live in application properties, never in the manifest. Environment passthrough SHALL always exclude the credential variables declared by the active tracker adapter: the launcher removes them from the CLI process environment regardless of passthrough configuration, so tracker credentials never reach the gnome.
<!-- implements FR11, UX2, D7 of add-agent-executor -->
<!-- implements NFR-S1 of add-tracker-port -->

#### Scenario: Tracker credentials scrubbed from the gnome
- **WHEN** a stage executes while `GNOMISH_GITHUB_TOKEN` is set in the factory environment
- **THEN** the CLI process environment contains no variable declared as a credential by the active tracker adapter
